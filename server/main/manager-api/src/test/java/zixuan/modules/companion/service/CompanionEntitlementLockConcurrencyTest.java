package zixuan.modules.companion.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import zixuan.modules.sys.dao.SysUserDao;

class CompanionEntitlementLockConcurrencyTest {
    @Test
    void sharedUserRowLockSerializesEntitlementGrantAndQuotaConsumption() throws Exception {
        Select lockQuery = SysUserDao.class.getMethod("selectByIdForUpdate", Long.class).getAnnotation(Select.class);
        assertTrue(String.join(" ", lockQuery.value()).toUpperCase().contains("FOR UPDATE"));

        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:entitlement_lock_" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE sys_user (id BIGINT PRIMARY KEY)");
        jdbc.update("INSERT INTO sys_user(id) VALUES (7)");
        TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        CountDownLatch firstLocked = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondLocked = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);

        try {
            var first = executor.submit(() -> transaction.executeWithoutResult(status -> {
                jdbc.queryForObject("SELECT id FROM sys_user WHERE id = 7 FOR UPDATE", Long.class);
                firstLocked.countDown();
                await(releaseFirst);
            }));
            assertTrue(firstLocked.await(2, TimeUnit.SECONDS));

            var second = executor.submit(() -> transaction.executeWithoutResult(status -> {
                jdbc.queryForObject("SELECT id FROM sys_user WHERE id = 7 FOR UPDATE", Long.class);
                secondLocked.countDown();
            }));

            assertFalse(secondLocked.await(Duration.ofMillis(200).toMillis(), TimeUnit.MILLISECONDS));
            releaseFirst.countDown();
            assertTrue(secondLocked.await(2, TimeUnit.SECONDS));
            first.get(2, TimeUnit.SECONDS);
            second.get(2, TimeUnit.SECONDS);
        } finally {
            releaseFirst.countDown();
            executor.shutdownNow();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(2, TimeUnit.SECONDS)) {
                throw new IllegalStateException("timed out waiting for lock release");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
