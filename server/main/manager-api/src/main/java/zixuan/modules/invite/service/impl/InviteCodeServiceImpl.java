package zixuan.modules.invite.service.impl;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import lombok.RequiredArgsConstructor;
import zixuan.common.exception.ErrorCode;
import zixuan.common.exception.RenException;
import zixuan.common.page.PageData;
import zixuan.modules.invite.dao.InviteCodeDao;
import zixuan.modules.invite.dao.InviteCodeUseDao;
import zixuan.modules.invite.dto.InviteCodeCreateDTO;
import zixuan.modules.invite.dto.InviteCodeDTO;
import zixuan.modules.invite.entity.InviteCodeEntity;
import zixuan.modules.invite.entity.InviteCodeUseEntity;
import zixuan.modules.invite.service.InviteCodeService;
import zixuan.modules.sys.service.SysParamsService;

@Service
@RequiredArgsConstructor
public class InviteCodeServiceImpl implements InviteCodeService {
    public static final String REQUIRED_PARAM = "app.invite_required";
    /** 去掉了容易看错的 0、O、1、I、L。 */
    private static final String ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
    private static final int CODE_LENGTH = 8;
    private static final int MAX_BATCH = 100;
    private static final int MAX_USES = 100_000;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final InviteCodeDao codeDao;
    private final InviteCodeUseDao useDao;
    private final SysParamsService sysParamsService;

    @Override
    public boolean isRequired() {
        // 只有明确配置为 false 才放开，参数缺失或读取不到时按“必须”处理。
        String value = sysParamsService.getValue(REQUIRED_PARAM, true);
        return !"false".equalsIgnoreCase(StringUtils.trimToEmpty(value));
    }

    @Override
    public void requireUsable(String code) {
        String normalized = normalize(code);
        if (normalized == null) {
            throw new RenException(ErrorCode.INVITE_CODE_REQUIRED);
        }
        InviteCodeEntity entity = codeDao.selectOne(new QueryWrapper<InviteCodeEntity>().eq("code", normalized));
        if (entity == null || !usable(entity)) {
            throw new RenException(ErrorCode.INVITE_CODE_INVALID);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void redeem(String code, Long userId) {
        String normalized = normalize(code);
        if (normalized == null) {
            throw new RenException(ErrorCode.INVITE_CODE_REQUIRED);
        }
        if (userId == null || codeDao.consume(normalized) != 1) {
            throw new RenException(ErrorCode.INVITE_CODE_INVALID);
        }
        InviteCodeEntity entity = codeDao.selectOne(new QueryWrapper<InviteCodeEntity>().eq("code", normalized));
        InviteCodeUseEntity use = new InviteCodeUseEntity();
        use.setId(newId());
        use.setCodeId(entity == null ? normalized : entity.getId());
        use.setUserId(userId);
        use.setUsedAt(new Date());
        useDao.insert(use);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<InviteCodeDTO> create(InviteCodeCreateDTO dto, Long operatorId) {
        InviteCodeCreateDTO body = dto == null ? new InviteCodeCreateDTO() : dto;
        int count = body.getCount() == null ? 1 : body.getCount();
        int maxUses = body.getMaxUses() == null ? 1 : body.getMaxUses();
        if (count < 1 || count > MAX_BATCH) {
            throw new RenException("一次可以生成 1 到 " + MAX_BATCH + " 个邀请码");
        }
        if (maxUses < 1 || maxUses > MAX_USES) {
            throw new RenException("可使用次数必须在 1 到 " + MAX_USES + " 之间");
        }
        if (body.getExpireDate() != null && !body.getExpireDate().after(new Date())) {
            throw new RenException("失效时间必须晚于现在");
        }
        String remark = StringUtils.trimToNull(body.getRemark());
        if (remark != null && remark.length() > 255) {
            throw new RenException("备注不能超过 255 个字");
        }
        List<InviteCodeDTO> created = new ArrayList<>();
        Date now = new Date();
        for (int index = 0; index < count; index++) {
            InviteCodeEntity entity = new InviteCodeEntity();
            entity.setId(newId());
            entity.setCode(uniqueCode());
            entity.setMaxUses(maxUses);
            entity.setUsedCount(0);
            entity.setExpireDate(body.getExpireDate());
            entity.setStatus(1);
            entity.setRemark(remark);
            entity.setCreator(operatorId);
            entity.setCreateDate(now);
            if (codeDao.insert(entity) != 1) {
                throw new RenException("邀请码生成失败");
            }
            created.add(toDTO(entity));
        }
        return created;
    }

    @Override
    public PageData<InviteCodeDTO> page(long page, long limit) {
        long safePage = Math.max(1, page);
        long safeLimit = Math.min(Math.max(1, limit), 100);
        IPage<InviteCodeEntity> result = codeDao.selectPage(new Page<>(safePage, safeLimit),
                new QueryWrapper<InviteCodeEntity>().orderByDesc("create_date", "id"));
        return new PageData<>(result.getRecords().stream().map(this::toDTO).toList(), result.getTotal());
    }

    @Override
    public void setEnabled(String id, boolean enabled) {
        InviteCodeEntity entity = StringUtils.isBlank(id) ? null : codeDao.selectById(id);
        if (entity == null) {
            throw new RenException("邀请码不存在");
        }
        InviteCodeEntity update = new InviteCodeEntity();
        update.setId(id);
        update.setStatus(enabled ? 1 : 0);
        codeDao.updateById(update);
    }

    @Override
    public void setRequired(boolean required) {
        sysParamsService.upsertValueByCode(REQUIRED_PARAM, String.valueOf(required), "boolean",
                "新手机号创建账号时是否必须填写邀请码");
    }

    private boolean usable(InviteCodeEntity entity) {
        return Integer.valueOf(1).equals(entity.getStatus())
                && entity.getUsedCount() != null && entity.getMaxUses() != null
                && entity.getUsedCount() < entity.getMaxUses()
                && (entity.getExpireDate() == null || entity.getExpireDate().after(new Date()));
    }

    private InviteCodeDTO toDTO(InviteCodeEntity entity) {
        InviteCodeDTO dto = new InviteCodeDTO();
        dto.setId(entity.getId());
        dto.setCode(entity.getCode());
        dto.setMaxUses(entity.getMaxUses());
        dto.setUsedCount(entity.getUsedCount());
        dto.setExpireDate(entity.getExpireDate());
        dto.setEnabled(Integer.valueOf(1).equals(entity.getStatus()));
        dto.setUsable(usable(entity));
        dto.setRemark(entity.getRemark());
        dto.setCreateDate(entity.getCreateDate());
        return dto;
    }

    /** 用户输入的邀请码不区分大小写，忽略首尾空白。 */
    private String normalize(String code) {
        String value = StringUtils.trimToNull(code);
        if (value == null) {
            return null;
        }
        return value.length() > 32 ? null : value.toUpperCase(Locale.ROOT);
    }

    private String uniqueCode() {
        for (int attempt = 0; attempt < 10; attempt++) {
            String code = randomCode();
            Long existing = codeDao.selectCount(new QueryWrapper<InviteCodeEntity>().eq("code", code));
            if (existing == null || existing == 0) {
                return code;
            }
        }
        throw new RenException("邀请码生成失败，请重试");
    }

    private String randomCode() {
        StringBuilder code = new StringBuilder(CODE_LENGTH);
        for (int index = 0; index < CODE_LENGTH; index++) {
            code.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return code.toString();
    }

    private String newId() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
