package zixuan.modules.sys.service;

import zixuan.common.page.PageData;
import zixuan.common.service.BaseService;
import zixuan.modules.sys.dto.AdminPageUserDTO;
import zixuan.modules.sys.dto.PasswordDTO;
import zixuan.modules.sys.dto.SysUserDTO;
import zixuan.modules.sys.entity.SysUserEntity;
import zixuan.modules.sys.vo.AdminPageUserVO;

/**
 * 系统用户
 */
public interface SysUserService extends BaseService<SysUserEntity> {

    SysUserDTO getByUsername(String username);

    SysUserDTO getByPhone(String phone);

    SysUserDTO getByUserId(Long userId);

    void save(SysUserDTO dto);

    /**
     * 保存消费者 App 注册用户：永远是普通用户，不执行"首个用户成超管"逻辑
     *
     * @param dto 用户信息（username/phone 必填，password 为空表示验证码直登注册）
     */
    void saveAppUser(SysUserDTO dto);

    /**
     * 删除指定用户，且有关联的数据设备和智能体
     * 
     * @param ids
     */
    void deleteById(Long ids);

    /**
     * 验证是否允许修改密码更改
     * 
     * @param userId      用户id
     * @param passwordDTO 验证密码的参数
     */
    void changePassword(Long userId, PasswordDTO passwordDTO);

    /**
     * 直接修改密码，不需要验证
     * 
     * @param userId   用户id
     * @param password 密码
     */
    void changePasswordDirectly(Long userId, String password);

    /**
     * 重置密码
     * 
     * @param userId 用户id
     * @return 随机生成符合规范的密码
     */
    String resetPassword(Long userId);

    /**
     * 管理员分页用户信息
     * 
     * @param dto 分页查找参数
     * @return 用户列表分页数据
     */
    PageData<AdminPageUserVO> page(AdminPageUserDTO dto);

    /**
     * 批量修改用户状态
     * 
     * @param status  用户状态
     * @param userIds 用户ID数组
     */
    void changeStatus(Integer status, String[] userIds);

    /**
     * 获取是否允许用户注册
     * 
     * @return 是否允许用户注册
     */
    boolean getAllowUserRegister();
}
