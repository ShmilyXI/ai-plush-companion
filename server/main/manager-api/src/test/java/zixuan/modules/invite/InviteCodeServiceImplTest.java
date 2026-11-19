package zixuan.modules.invite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Date;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.MessageSource;
import org.springframework.test.util.ReflectionTestUtils;

import com.baomidou.mybatisplus.core.conditions.Wrapper;

import zixuan.common.exception.ErrorCode;
import zixuan.common.exception.RenException;
import zixuan.common.utils.MessageUtils;
import zixuan.modules.invite.dao.InviteCodeDao;
import zixuan.modules.invite.dao.InviteCodeUseDao;
import zixuan.modules.invite.dto.InviteCodeCreateDTO;
import zixuan.modules.invite.dto.InviteCodeDTO;
import zixuan.modules.invite.entity.InviteCodeEntity;
import zixuan.modules.invite.entity.InviteCodeUseEntity;
import zixuan.modules.invite.service.impl.InviteCodeServiceImpl;
import zixuan.modules.sys.service.SysParamsService;

class InviteCodeServiceImplTest {
    private InviteCodeDao codeDao;
    private InviteCodeUseDao useDao;
    private SysParamsService params;
    private InviteCodeServiceImpl service;

    @BeforeAll
    static void injectMessageSource() {
        ReflectionTestUtils.setField(MessageUtils.class, "messageSource", mock(MessageSource.class));
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        codeDao = mock(InviteCodeDao.class);
        useDao = mock(InviteCodeUseDao.class);
        params = mock(SysParamsService.class);
        service = new InviteCodeServiceImpl(codeDao, useDao, params);
        when(codeDao.insert(any(InviteCodeEntity.class))).thenReturn(1);
        when(codeDao.selectCount(any(Wrapper.class))).thenReturn(0L);
    }

    @Test
    void invitesAreRequiredUnlessExplicitlyTurnedOff() {
        when(params.getValue("app.invite_required", true)).thenReturn(null, "true", "", "FALSE", "false");

        assertTrue(service.isRequired());
        assertTrue(service.isRequired());
        assertTrue(service.isRequired());
        assertFalse(service.isRequired());
        assertFalse(service.isRequired());
    }

    @Test
    @SuppressWarnings("unchecked")
    void usabilityCheckCoversMissingDisabledExhaustedAndExpiredCodes() {
        assertEquals(ErrorCode.INVITE_CODE_REQUIRED,
                assertThrows(RenException.class, () -> service.requireUsable("  ")).getCode());

        when(codeDao.selectOne(any(Wrapper.class))).thenReturn(
                null,
                code(0, 1, 0, null),
                code(1, 1, 1, null),
                code(1, 5, 0, new Date(System.currentTimeMillis() - 1000)),
                code(1, 5, 4, new Date(System.currentTimeMillis() + 60_000)));
        for (int index = 0; index < 4; index++) {
            assertEquals(ErrorCode.INVITE_CODE_INVALID,
                    assertThrows(RenException.class, () -> service.requireUsable("abcd2345")).getCode());
        }
        service.requireUsable("abcd2345");
        verify(codeDao, never()).consume(any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void redeemConsumesAtomicallyAndRecordsWhoUsedIt() {
        InviteCodeEntity entity = code(1, 5, 1, null);
        entity.setId("code-1");
        when(codeDao.consume("ABCD2345")).thenReturn(1);
        when(codeDao.selectOne(any(Wrapper.class))).thenReturn(entity);

        service.redeem(" abcd2345 ", 9L);

        ArgumentCaptor<InviteCodeUseEntity> use = ArgumentCaptor.forClass(InviteCodeUseEntity.class);
        verify(useDao).insert(use.capture());
        assertEquals("code-1", use.getValue().getCodeId());
        assertEquals(9L, use.getValue().getUserId());
    }

    @Test
    void redeemFailsWhenTheLastSlotWasTakenConcurrently() {
        when(codeDao.consume("ABCD2345")).thenReturn(0);

        RenException error = assertThrows(RenException.class, () -> service.redeem("ABCD2345", 9L));

        assertEquals(ErrorCode.INVITE_CODE_INVALID, error.getCode());
        verify(useDao, never()).insert(any(InviteCodeUseEntity.class));
    }

    @Test
    void createGeneratesDistinctReadableCodes() {
        InviteCodeCreateDTO dto = new InviteCodeCreateDTO();
        dto.setCount(3);
        dto.setMaxUses(2);
        dto.setRemark(" 第一批内测 ");

        List<InviteCodeDTO> created = service.create(dto, 1L);

        assertEquals(3, created.size());
        assertNotEquals(created.get(0).getCode(), created.get(1).getCode());
        for (InviteCodeDTO code : created) {
            assertTrue(code.getCode().matches("[A-HJ-KM-NP-Z2-9]{8}"), code.getCode());
            assertEquals(2, code.getMaxUses());
            assertEquals("第一批内测", code.getRemark());
            assertTrue(code.isUsable());
        }
    }

    @Test
    void createRejectsUnreasonableRequests() {
        InviteCodeCreateDTO tooMany = new InviteCodeCreateDTO();
        tooMany.setCount(101);
        InviteCodeCreateDTO noUses = new InviteCodeCreateDTO();
        noUses.setMaxUses(0);
        InviteCodeCreateDTO expired = new InviteCodeCreateDTO();
        expired.setExpireDate(new Date(System.currentTimeMillis() - 1000));

        for (InviteCodeCreateDTO dto : List.of(tooMany, noUses, expired)) {
            assertThrows(RenException.class, () -> service.create(dto, 1L));
        }
        verify(codeDao, never()).insert(any(InviteCodeEntity.class));
    }

    private InviteCodeEntity code(int status, int maxUses, int usedCount, Date expireDate) {
        InviteCodeEntity entity = new InviteCodeEntity();
        entity.setId("id");
        entity.setCode("ABCD2345");
        entity.setStatus(status);
        entity.setMaxUses(maxUses);
        entity.setUsedCount(usedCount);
        entity.setExpireDate(expireDate);
        return entity;
    }
}
