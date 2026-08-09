package xiaozhi.modules.companion.service.impl;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import xiaozhi.common.exception.RenException;
import xiaozhi.modules.companion.service.CompanionAuditService;
import xiaozhi.modules.device.entity.OtaEntity;
import xiaozhi.modules.device.service.OtaService;

class AdminFirmwareServiceImplTest {
    private OtaService otaService;
    private CompanionAuditService auditService;
    private AdminFirmwareServiceImpl service;

    @BeforeEach
    void setUp() {
        otaService = mock(OtaService.class);
        auditService = mock(CompanionAuditService.class);
        service = new AdminFirmwareServiceImpl(otaService, auditService);
        when(otaService.normalizeTypeKey("esp32")).thenReturn("esp32");
        when(otaService.normalizeTypeKey("   ")).thenReturn("__default__");
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void databaseFailureDeletesTheNewFileAfterRollback() {
        when(otaService.storeFirmware(any())).thenReturn("uploadfile/new.bin");
        when(otaService.save(any(OtaEntity.class))).thenReturn(false);

        assertThrows(RenException.class, () -> upload());
        complete(TransactionSynchronization.STATUS_ROLLED_BACK);

        verify(otaService).deleteFirmwareFile("uploadfile/new.bin");
        verify(auditService, never()).record(any(), any(), any(), any(), any(), any());
    }

    @Test
    void auditFailureDeletesTheNewFileAfterRollback() {
        when(otaService.storeFirmware(any())).thenReturn("uploadfile/new.bin");
        when(otaService.save(any(OtaEntity.class))).thenReturn(true);
        org.mockito.Mockito.doThrow(new RenException("审计记录写入失败"))
                .when(auditService).record(any(), any(), any(), any(), any(), any());

        assertThrows(RenException.class, () -> upload());
        complete(TransactionSynchronization.STATUS_ROLLED_BACK);

        verify(otaService).deleteFirmwareFile("uploadfile/new.bin");
    }

    @Test
    void successfulReplacementDeletesOnlyTheOldFileAfterCommit() {
        OtaEntity old = firmware("old-id", "uploadfile/old.bin");
        when(otaService.selectByNormalizedTypeForUpdate("esp32")).thenReturn(old);
        when(otaService.selectByIdForUpdate("old-id")).thenReturn(old);
        when(otaService.storeFirmware(any())).thenReturn("uploadfile/new.bin");
        when(otaService.save(any(OtaEntity.class))).thenReturn(true);

        upload();
        verify(otaService, never()).deleteFirmwareFile(any());
        commit();

        verify(otaService).deleteFirmwareFile("uploadfile/old.bin");
        verify(otaService, never()).deleteFirmwareFile("uploadfile/new.bin");
    }

    @Test
    void deleteRollbackKeepsTheFirmwareFile() {
        lockedFirmware("f1", "uploadfile/kept.bin", "esp32");
        when(otaService.deleteById("f1")).thenReturn(true);

        service.delete(7L, "f1");
        complete(TransactionSynchronization.STATUS_ROLLED_BACK);

        verify(otaService, never()).deleteFirmwareFile(any());
    }

    @Test
    void deleteCommitRemovesTheFirmwareFile() {
        lockedFirmware("f1", "uploadfile/deleted.bin", "esp32");
        when(otaService.deleteById("f1")).thenReturn(true);

        service.delete(7L, "f1");
        commit();

        verify(otaService).deleteFirmwareFile("uploadfile/deleted.bin");
        verify(auditService).record(eq(7L), eq(null), eq("firmware.delete"), eq("firmware"), eq("f1"), eq(java.util.Map.of()));
    }

    @Test
    void committedDeleteKeepsAFileStillReferencedByAnotherRecord() {
        lockedFirmware("f1", "uploadfile/shared.bin", "esp32");
        when(otaService.deleteById("f1")).thenReturn(true);
        when(otaService.countByFirmwarePath("uploadfile/shared.bin")).thenReturn(1L);

        service.delete(7L, "f1");
        commit();

        verify(otaService, never()).deleteFirmwareFile("uploadfile/shared.bin");
    }

    @Test
    void committedCleanupFailureDoesNotFailTheSuccessfulMutation() {
        lockedFirmware("f1", "uploadfile/deleted.bin", "esp32");
        when(otaService.deleteById("f1")).thenReturn(true);
        when(otaService.countByFirmwarePath("uploadfile/deleted.bin")).thenReturn(0L);
        org.mockito.Mockito.doThrow(new RenException("磁盘暂时不可用"))
                .when(otaService).deleteFirmwareFile("uploadfile/deleted.bin");

        service.delete(7L, "f1");

        assertDoesNotThrow(this::commit);
    }

    @Test
    void deletingAMissingFirmwareDoesNotAudit() {
        assertThrows(RenException.class, () -> service.delete(7L, "missing"));

        verify(otaService, never()).deleteById(any());
        verify(auditService, never()).record(any(), any(), any(), any(), any(), any());
    }

    @Test
    void missingTransactionSynchronizationDoesNotLeaveTheNewFile() {
        TransactionSynchronizationManager.clearSynchronization();

        assertThrows(IllegalStateException.class, () -> upload());

        verify(otaService, never()).storeFirmware(any());
        verify(otaService, never()).deleteFirmwareFile(any());
        verify(otaService, never()).save(any(OtaEntity.class));
    }

    @Test
    void uploadLocksTheNormalizedTypeBeforeWritingTheFile() {
        when(otaService.storeFirmware(any())).thenReturn("uploadfile/new.bin");
        when(otaService.save(any(OtaEntity.class))).thenReturn(true);

        upload();

        var ordered = inOrder(otaService);
        ordered.verify(otaService).selectByNormalizedTypeForUpdate("esp32");
        ordered.verify(otaService).storeFirmware(any());
    }

    @Test
    void blankTypeUsesTheSingleDefaultSlot() {
        when(otaService.storeFirmware(any())).thenReturn("uploadfile/default.bin");
        when(otaService.save(any(OtaEntity.class))).thenReturn(true);

        service.upload(7L,
                new MockMultipartFile("file", "firmware.bin", "application/octet-stream", new byte[] { 1 }),
                "默认固件", "   ", "1.0.0", null);

        verify(otaService).selectByNormalizedTypeForUpdate("__default__");
    }

    @Test
    void duplicateSlotConflictRollsBackAndDeletesTheNewFile() {
        when(otaService.storeFirmware(any())).thenReturn("uploadfile/conflict.bin");
        when(otaService.save(any(OtaEntity.class))).thenThrow(new DuplicateKeyException("duplicate slot"));

        assertThrows(RenException.class, this::upload);
        complete(TransactionSynchronization.STATUS_ROLLED_BACK);

        verify(otaService).deleteFirmwareFile("uploadfile/conflict.bin");
    }

    @Test
    void concurrentSlotLockConflictReturnsTheSameStableConflict() {
        when(otaService.storeFirmware(any())).thenReturn("uploadfile/locked.bin");
        when(otaService.save(any(OtaEntity.class))).thenThrow(new CannotAcquireLockException("slot locked"));

        assertThrows(RenException.class, this::upload);
        complete(TransactionSynchronization.STATUS_ROLLED_BACK);

        verify(otaService).deleteFirmwareFile("uploadfile/locked.bin");
    }

    @Test
    void deleteLocksTheRecordBeforeDeletingIt() {
        lockedFirmware("f1", "uploadfile/deleted.bin", "esp32");
        when(otaService.deleteById("f1")).thenReturn(true);

        service.delete(7L, "f1");

        var ordered = inOrder(otaService);
        ordered.verify(otaService).selectById("f1");
        ordered.verify(otaService).selectByNormalizedTypeForUpdate("esp32");
        ordered.verify(otaService).selectByIdForUpdate("f1");
        ordered.verify(otaService).deleteById("f1");
    }

    @Test
    void metadataUpdateLocksTheRecordAndDestinationSlotWithoutAcceptingFileFields() {
        OtaEntity existing = firmware("f1", "uploadfile/original.bin");
        existing.setSize(42L);
        OtaEntity update = firmware(null, "/etc/hosts");
        update.setType(" ASR ");
        update.setSize(1L);
        update.setCreator(99L);
        update.setUpdater(99L);
        when(otaService.normalizeTypeKey("esp32")).thenReturn("esp32");
        when(otaService.normalizeTypeKey(" ASR ")).thenReturn("asr");
        when(otaService.selectById("f1")).thenReturn(existing);
        when(otaService.selectByNormalizedTypeForUpdate("esp32")).thenReturn(existing);
        when(otaService.selectByIdForUpdate("f1")).thenReturn(existing);
        when(otaService.updateById(any(OtaEntity.class))).thenReturn(true);

        service.update(7L, "f1", update);

        var ordered = inOrder(otaService);
        ordered.verify(otaService).selectById("f1");
        ordered.verify(otaService).selectByNormalizedTypeForUpdate("asr");
        ordered.verify(otaService).selectByNormalizedTypeForUpdate("esp32");
        ordered.verify(otaService).selectByIdForUpdate("f1");
        org.mockito.ArgumentCaptor<OtaEntity> saved = org.mockito.ArgumentCaptor.forClass(OtaEntity.class);
        ordered.verify(otaService).updateById(saved.capture());
        org.junit.jupiter.api.Assertions.assertEquals("uploadfile/original.bin", saved.getValue().getFirmwarePath());
        org.junit.jupiter.api.Assertions.assertEquals(42L, saved.getValue().getSize());
        org.junit.jupiter.api.Assertions.assertEquals(" ASR ", saved.getValue().getType());
        org.junit.jupiter.api.Assertions.assertEquals(null, saved.getValue().getCreator());
        org.junit.jupiter.api.Assertions.assertEquals(null, saved.getValue().getUpdater());
        verify(auditService).record(eq(7L), eq(null), eq("firmware.update"), eq("firmware"), eq("f1"), any());
    }

    @Test
    void failedAffectedRowUpdateDoesNotAudit() {
        lockedFirmware("f1", "uploadfile/original.bin", "esp32");
        when(otaService.updateById(any(OtaEntity.class))).thenReturn(false);

        assertThrows(RenException.class, () -> service.update(7L, "f1", firmware(null, "/etc/hosts")));

        verify(auditService, never()).record(any(), any(), any(), any(), any(), any());
    }

    @Test
    void failedAffectedRowDeleteDoesNotAuditOrScheduleFileCleanup() {
        lockedFirmware("f1", "uploadfile/original.bin", "esp32");
        when(otaService.deleteById("f1")).thenReturn(false);

        assertThrows(RenException.class, () -> service.delete(7L, "f1"));
        commit();

        verify(auditService, never()).record(any(), any(), any(), any(), any(), any());
        verify(otaService, never()).deleteFirmwareFile(any());
    }

    @Test
    void failedLegacyBatchDeleteRollsBackAllFileCleanup() {
        lockedFirmware("f1", "uploadfile/one.bin", "a");
        lockedFirmware("f2", "uploadfile/two.bin", "b");
        when(otaService.deleteById("f1")).thenReturn(true);
        when(otaService.deleteById("f2")).thenReturn(false);

        assertThrows(RenException.class, () -> service.deleteAll(7L, new String[] { "f1", "f2" }));
        complete(TransactionSynchronization.STATUS_ROLLED_BACK);

        verify(otaService, never()).deleteFirmwareFile(any());
    }

    private String upload() {
        return service.upload(7L,
                new MockMultipartFile("file", "firmware.bin", "application/octet-stream", new byte[] { 1, 2, 3 }),
                "稳定固件", "esp32", "1.0.0", null);
    }

    private OtaEntity firmware(String id, String path) {
        OtaEntity entity = new OtaEntity();
        entity.setId(id);
        entity.setFirmwareName("固件");
        entity.setType("esp32");
        entity.setVersion("1.0.0");
        entity.setFirmwarePath(path);
        return entity;
    }

    private OtaEntity lockedFirmware(String id, String path, String type) {
        OtaEntity entity = firmware(id, path);
        entity.setType(type);
        when(otaService.normalizeTypeKey(type)).thenReturn(type);
        when(otaService.selectById(id)).thenReturn(entity);
        when(otaService.selectByNormalizedTypeForUpdate(type)).thenReturn(entity);
        when(otaService.selectByIdForUpdate(id)).thenReturn(entity);
        return entity;
    }

    private void commit() {
        List<TransactionSynchronization> synchronizations = TransactionSynchronizationManager.getSynchronizations();
        synchronizations.forEach(TransactionSynchronization::afterCommit);
        synchronizations.forEach(value -> value.afterCompletion(TransactionSynchronization.STATUS_COMMITTED));
    }

    private void complete(int status) {
        TransactionSynchronizationManager.getSynchronizations().forEach(value -> value.afterCompletion(status));
    }
}
