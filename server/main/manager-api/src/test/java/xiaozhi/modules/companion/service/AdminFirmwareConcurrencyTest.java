package xiaozhi.modules.companion.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.io.IOException;
import java.io.Serializable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.web.multipart.MultipartFile;

import com.baomidou.mybatisplus.core.conditions.Wrapper;

import xiaozhi.common.exception.RenException;
import xiaozhi.common.page.PageData;
import xiaozhi.modules.companion.service.impl.AdminFirmwareServiceImpl;
import xiaozhi.modules.device.entity.OtaEntity;
import xiaozhi.modules.device.service.OtaService;

class AdminFirmwareConcurrencyTest {
    @TempDir
    Path firmwareDirectory;

    @Test
    void concurrentUploadsForTheSameTypeLeaveOneRowAndOneFile() throws Exception {
        Fixture fixture = fixture();
        fixture.ota.absentSlotBarrier = new CyclicBarrier(2);

        List<Throwable> failures = concurrentUploads(fixture, " ESP32 ", "esp32");

        assertEquals(1, failures.size());
        assertTrue(failures.get(0) instanceof RenException, failures.toString());
        assertSingleDatabaseFilePair(fixture);
    }

    @Test
    void concurrentBlankAndNullTypeUploadsShareTheDefaultSlot() throws Exception {
        Fixture fixture = fixture();
        fixture.ota.absentSlotBarrier = new CyclicBarrier(2);

        List<Throwable> failures = concurrentUploads(fixture, "   ", null);

        assertEquals(1, failures.size());
        assertTrue(failures.get(0) instanceof RenException, failures.toString());
        assertSingleDatabaseFilePair(fixture);
        assertEquals("__default__", fixture.jdbc.queryForObject(
                "SELECT normalized_type_key FROM ai_ota", String.class));
    }

    @Test
    void uploadThenConcurrentDeleteLeavesNoRowAndNoOrphanFile() throws Exception {
        Fixture fixture = fixture();
        Path oldFile = Files.write(firmwareDirectory.resolve("old.bin"), new byte[] { 9 });
        fixture.jdbc.update("INSERT INTO ai_ota(id, firmware_name, type, version, firmware_path)"
                + " VALUES ('slot-1', '旧固件', 'esp32', '0.9.0', ?)", oldFile.toString());
        fixture.ota.pauseNextTypeLock = true;
        var executor = Executors.newFixedThreadPool(2);

        try {
            Future<String> upload = executor.submit(() -> fixture.service.upload(7L, file("new.bin"),
                    "新固件", "esp32", "1.0.0", null));
            assertTrue(fixture.ota.typeLocked.await(2, TimeUnit.SECONDS));
            Future<?> delete = executor.submit(() -> fixture.service.delete(7L, "slot-1"));

            assertFalse(delete.isDone());
            fixture.ota.releaseTypeLock.countDown();
            upload.get(3, TimeUnit.SECONDS);
            delete.get(3, TimeUnit.SECONDS);
        } finally {
            fixture.ota.releaseTypeLock.countDown();
            executor.shutdownNow();
        }

        assertEquals(0, fixture.jdbc.queryForObject("SELECT COUNT(*) FROM ai_ota", Integer.class));
        assertEquals(0, managedFileCount());
    }

    @Test
    void oppositeTypeChangesUseOneStableLockOrderWithoutDeadlock() throws Exception {
        Fixture fixture = fixture();
        insertFirmware(fixture, "firmware-a", "A", "a.bin");
        insertFirmware(fixture, "firmware-b", "B", "b.bin");
        fixture.ota.idBeforeSlotBarrier = new CyclicBarrier(2);
        var executor = Executors.newFixedThreadPool(2);

        List<Throwable> failures;
        try {
            Future<?> first = executor.submit(() -> fixture.service.update(7L, "firmware-a", metadata("B")));
            Future<?> second = executor.submit(() -> fixture.service.update(8L, "firmware-b", metadata("A")));
            failures = java.util.Arrays.asList(failure(first), failure(second));
        } finally {
            executor.shutdownNow();
        }

        assertTrue(failures.stream().allMatch(RenException.class::isInstance), failures.toString());
        assertEquals(2, fixture.jdbc.queryForObject("SELECT COUNT(*) FROM ai_ota", Integer.class));
        assertEquals(2, fixture.jdbc.queryForObject(
                "SELECT COUNT(DISTINCT normalized_type_key) FROM ai_ota", Integer.class));
        assertEquals(2, managedFileCount());
        assertEquals(0, fixture.audit.count("firmware.update", "firmware-a"));
        assertEquals(0, fixture.audit.count("firmware.update", "firmware-b"));
    }

    @Test
    void reversedBulkDeletesUseStableSlotAndRowOrderWithoutDuplicateAudit() throws Exception {
        Fixture fixture = fixture();
        Path shared = Files.write(firmwareDirectory.resolve("shared.bin"), new byte[] { 9 });
        insertFirmware(fixture, "firmware-a", "A", shared);
        insertFirmware(fixture, "firmware-b", "B", shared);
        fixture.ota.idBeforeSlotBarrier = new CyclicBarrier(2);
        var executor = Executors.newFixedThreadPool(2);

        List<Throwable> failures;
        try {
            Future<?> first = executor.submit(
                    () -> fixture.service.deleteAll(7L, new String[] { "firmware-a", "firmware-b" }));
            Future<?> second = executor.submit(
                    () -> fixture.service.deleteAll(8L, new String[] { "firmware-b", "firmware-a" }));
            failures = java.util.Arrays.asList(failure(first), failure(second));
        } finally {
            executor.shutdownNow();
        }

        assertEquals(1, failures.stream().filter(java.util.Objects::isNull).count(), failures.toString());
        assertTrue(failures.stream().filter(java.util.Objects::nonNull)
                .allMatch(RenException.class::isInstance), failures.toString());
        assertEquals(0, fixture.jdbc.queryForObject("SELECT COUNT(*) FROM ai_ota", Integer.class));
        assertEquals(0, managedFileCount());
        assertEquals(1, fixture.audit.count("firmware.delete", "firmware-a"));
        assertEquals(1, fixture.audit.count("firmware.delete", "firmware-b"));
    }

    private List<Throwable> concurrentUploads(Fixture fixture, String firstType, String secondType) throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        try {
            Future<String> first = executor.submit(() -> fixture.service.upload(7L, file("first.bin"),
                    "固件一", firstType, "1.0.0", null));
            Future<String> second = executor.submit(() -> fixture.service.upload(8L, file("second.bin"),
                    "固件二", secondType, "1.0.1", null));
            return java.util.stream.Stream.of(first, second)
                    .map(this::failure)
                    .filter(java.util.Objects::nonNull)
                    .toList();
        } finally {
            executor.shutdownNow();
        }
    }

    private Throwable failure(Future<?> future) {
        try {
            future.get(4, TimeUnit.SECONDS);
            return null;
        } catch (ExecutionException exception) {
            return exception.getCause();
        } catch (Exception exception) {
            return exception;
        }
    }

    private void assertSingleDatabaseFilePair(Fixture fixture) throws IOException {
        assertEquals(1, fixture.jdbc.queryForObject("SELECT COUNT(*) FROM ai_ota", Integer.class));
        String path = fixture.jdbc.queryForObject("SELECT firmware_path FROM ai_ota", String.class);
        assertTrue(Files.exists(Path.of(path)));
        assertEquals(1, managedFileCount());
    }

    private long managedFileCount() throws IOException {
        try (var paths = Files.list(firmwareDirectory)) {
            return paths.filter(Files::isRegularFile).count();
        }
    }

    private MockMultipartFile file(String name) {
        return new MockMultipartFile("file", name, "application/octet-stream", new byte[] { 1, 2, 3 });
    }

    private OtaEntity metadata(String type) {
        OtaEntity entity = new OtaEntity();
        entity.setFirmwareName("更新固件");
        entity.setType(type);
        entity.setVersion("2.0.0");
        return entity;
    }

    private void insertFirmware(Fixture fixture, String id, String type, String filename) throws IOException {
        insertFirmware(fixture, id, type, Files.write(firmwareDirectory.resolve(filename), new byte[] { 1 }));
    }

    private void insertFirmware(Fixture fixture, String id, String type, Path path) {
        fixture.jdbc.update("INSERT INTO ai_ota(id, firmware_name, type, version, firmware_path)"
                + " VALUES (?, '固件', ?, '1.0.0', ?)", id, type, path.toString());
    }

    private Fixture fixture() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:firmware_" + System.nanoTime()
                        + ";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000",
                "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE ai_ota (id VARCHAR(64) PRIMARY KEY, firmware_name VARCHAR(255),"
                + " type VARCHAR(255), version VARCHAR(64), firmware_path VARCHAR(1024), size BIGINT, remark VARCHAR(255),"
                + " normalized_type_key VARCHAR(255) GENERATED ALWAYS AS"
                + " (LOWER(CASE WHEN TRIM(COALESCE(type, '')) = '' THEN '__default__' ELSE TRIM(type) END)),"
                + " CONSTRAINT uk_ai_ota_normalized_type UNIQUE(normalized_type_key))");
        H2OtaService ota = new H2OtaService(jdbc, firmwareDirectory);
        RecordingAuditService audit = new RecordingAuditService();
        AdminFirmwareService target = new AdminFirmwareServiceImpl(ota, audit);
        ProxyFactory proxy = new ProxyFactory(target);
        proxy.setInterfaces(AdminFirmwareService.class);
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(dataSource),
                new AnnotationTransactionAttributeSource()));
        return new Fixture(jdbc, ota, audit, (AdminFirmwareService) proxy.getProxy());
    }

    private record Fixture(JdbcTemplate jdbc, H2OtaService ota, RecordingAuditService audit,
            AdminFirmwareService service) {
    }

    private static final class H2OtaService implements OtaService {
        private final JdbcTemplate jdbc;
        private final Path directory;
        private volatile CyclicBarrier absentSlotBarrier;
        private volatile boolean pauseNextTypeLock;
        private volatile CyclicBarrier idBeforeSlotBarrier;
        private final ThreadLocal<Boolean> slotLocked = ThreadLocal.withInitial(() -> false);
        private final CountDownLatch typeLocked = new CountDownLatch(1);
        private final CountDownLatch releaseTypeLock = new CountDownLatch(1);

        private H2OtaService(JdbcTemplate jdbc, Path directory) {
            this.jdbc = jdbc;
            this.directory = directory;
        }

        @Override
        public String normalizeTypeKey(String type) {
            String normalized = type == null ? "" : type.trim().toLowerCase(Locale.ROOT);
            return normalized.isEmpty() ? "__default__" : normalized;
        }

        @Override
        public OtaEntity selectByNormalizedTypeForUpdate(String normalizedType) {
            slotLocked.set(true);
            List<OtaEntity> rows = jdbc.query("SELECT id, firmware_name, type, version, firmware_path, size, remark"
                    + " FROM ai_ota WHERE normalized_type_key = ? FOR UPDATE", (result, row) -> firmware(result),
                    normalizedType);
            if (rows.isEmpty() && absentSlotBarrier != null) {
                await(absentSlotBarrier);
            }
            if (pauseNextTypeLock) {
                pauseNextTypeLock = false;
                typeLocked.countDown();
                await(releaseTypeLock);
            }
            return rows.isEmpty() ? null : rows.get(0);
        }

        @Override
        public OtaEntity selectByIdForUpdate(String id) {
            if (idBeforeSlotBarrier != null && !slotLocked.get()) {
                await(idBeforeSlotBarrier);
            }
            List<OtaEntity> rows = jdbc.query("SELECT id, firmware_name, type, version, firmware_path, size, remark"
                    + " FROM ai_ota WHERE id = ? FOR UPDATE", (result, row) -> firmware(result), id);
            return rows.isEmpty() ? null : rows.get(0);
        }

        @Override
        public boolean save(OtaEntity entity) {
            if (entity.getId() == null) {
                entity.setId(UUID.randomUUID().toString());
                return jdbc.update("INSERT INTO ai_ota(id, firmware_name, type, version, firmware_path, size, remark)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?)", entity.getId(), entity.getFirmwareName(), entity.getType(),
                        entity.getVersion(), entity.getFirmwarePath(), entity.getSize(), entity.getRemark()) == 1;
            }
            return updateById(entity);
        }

        @Override
        public boolean updateById(OtaEntity entity) {
            return jdbc.update("UPDATE ai_ota SET firmware_name=?, type=?, version=?, firmware_path=?, size=?, remark=?"
                    + " WHERE id=?", entity.getFirmwareName(), entity.getType(), entity.getVersion(),
                    entity.getFirmwarePath(), entity.getSize(), entity.getRemark(), entity.getId()) == 1;
        }

        @Override
        public boolean deleteById(Serializable id) {
            return jdbc.update("DELETE FROM ai_ota WHERE id=?", id) == 1;
        }

        @Override
        public long countByFirmwarePath(String firmwarePath) {
            return jdbc.queryForObject("SELECT COUNT(*) FROM ai_ota WHERE firmware_path=?", Long.class, firmwarePath);
        }

        @Override
        public String storeFirmware(MultipartFile file) {
            try {
                Path path = directory.resolve(UUID.randomUUID() + ".bin");
                Files.write(path, file.getBytes());
                return path.toString();
            } catch (IOException exception) {
                throw new RenException("测试固件写入失败", exception);
            }
        }

        @Override
        public Path resolveManagedFirmwareFile(String firmwarePath) {
            try {
                Path resolved = Path.of(firmwarePath).toRealPath();
                if (!resolved.startsWith(directory.toRealPath()) || !Files.isRegularFile(resolved)) {
                    throw new RenException("测试固件路径不合法");
                }
                return resolved;
            } catch (IOException exception) {
                throw new RenException("测试固件不存在", exception);
            }
        }

        @Override
        public void deleteFirmwareFile(String firmwarePath) {
            try {
                Files.deleteIfExists(Path.of(firmwarePath));
            } catch (IOException exception) {
                throw new RenException("测试固件删除失败", exception);
            }
        }

        @Override
        public OtaEntity selectById(Serializable id) {
            List<OtaEntity> rows = jdbc.query("SELECT id, firmware_name, type, version, firmware_path, size, remark"
                    + " FROM ai_ota WHERE id=?", (result, row) -> firmware(result), id);
            return rows.isEmpty() ? null : rows.get(0);
        }

        @Override
        public OtaEntity getLatestOta(String type) {
            List<OtaEntity> rows = jdbc.query("SELECT id, firmware_name, type, version, firmware_path, size, remark"
                    + " FROM ai_ota WHERE normalized_type_key=?", (result, row) -> firmware(result),
                    normalizeTypeKey(type));
            return rows.isEmpty() ? null : rows.get(0);
        }

        private static OtaEntity firmware(java.sql.ResultSet result) throws java.sql.SQLException {
            OtaEntity entity = new OtaEntity();
            entity.setId(result.getString("id"));
            entity.setFirmwareName(result.getString("firmware_name"));
            entity.setType(result.getString("type"));
            entity.setVersion(result.getString("version"));
            entity.setFirmwarePath(result.getString("firmware_path"));
            entity.setSize(result.getObject("size", Long.class));
            entity.setRemark(result.getString("remark"));
            return entity;
        }

        private static void await(CyclicBarrier barrier) {
            try {
                barrier.await(3, TimeUnit.SECONDS);
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        }

        private static void await(CountDownLatch latch) {
            try {
                if (!latch.await(3, TimeUnit.SECONDS)) throw new IllegalStateException("lock wait timed out");
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(exception);
            }
        }

        @Override public Class<OtaEntity> currentModelClass() { return OtaEntity.class; }
        @Override public boolean insert(OtaEntity entity) { return save(entity); }
        @Override public boolean insertBatch(Collection<OtaEntity> entities) { throw new UnsupportedOperationException(); }
        @Override public boolean insertBatch(Collection<OtaEntity> entities, int size) { throw new UnsupportedOperationException(); }
        @Override public boolean update(OtaEntity entity, Wrapper<OtaEntity> wrapper) { throw new UnsupportedOperationException(); }
        @Override public boolean updateBatchById(Collection<OtaEntity> entities) { throw new UnsupportedOperationException(); }
        @Override public boolean updateBatchById(Collection<OtaEntity> entities, int size) { throw new UnsupportedOperationException(); }
        @Override public boolean deleteBatchIds(Collection<? extends Serializable> ids) { throw new UnsupportedOperationException(); }
        @Override public PageData<OtaEntity> page(Map<String, Object> params) { throw new UnsupportedOperationException(); }
        @Override public void update(OtaEntity entity) { throw new UnsupportedOperationException(); }
        @Override public void delete(String[] ids) { throw new UnsupportedOperationException(); }
    }

    private static final class RecordingAuditService implements CompanionAuditService {
        private final Map<String, Integer> records = new ConcurrentHashMap<>();

        @Override
        public void record(Long operatorId, Long targetUserId, String action, String resourceType,
                String resourceId, Map<String, ?> details) {
            records.merge(action + ":" + resourceId, 1, Integer::sum);
        }

        private int count(String action, String resourceId) {
            return records.getOrDefault(action + ":" + resourceId, 0);
        }

        @Override
        public PageData<xiaozhi.modules.companion.entity.CompanionAuditEntity> page(
                int page, int limit, String keyword) {
            throw new UnsupportedOperationException();
        }
    }
}
