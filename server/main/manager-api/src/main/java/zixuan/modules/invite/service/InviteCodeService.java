package zixuan.modules.invite.service;

import java.util.List;

import zixuan.common.page.PageData;
import zixuan.modules.invite.dto.InviteCodeCreateDTO;
import zixuan.modules.invite.dto.InviteCodeDTO;

/**
 * 邀请码。只约束新账号的创建，已有账号登录不需要邀请码。
 */
public interface InviteCodeService {

    /**
     * 新手机号创建账号时是否必须填写邀请码。由系统参数 app.invite_required 控制，缺省为必须。
     */
    boolean isRequired();

    /**
     * 只读检查邀请码现在是否可用，不占用名额。不可用时抛出可直接展示的错误。
     */
    void requireUsable(String code);

    /**
     * 占用一次名额并记录使用者。需要在创建账号的事务里调用，失败时账号一并回滚。
     */
    void redeem(String code, Long userId);

    List<InviteCodeDTO> create(InviteCodeCreateDTO dto, Long operatorId);

    PageData<InviteCodeDTO> page(long page, long limit);

    void setEnabled(String id, boolean enabled);

    void setRequired(boolean required);
}
