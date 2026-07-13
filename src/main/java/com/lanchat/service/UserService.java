package com.lanchat.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.lanchat.dto.ChangePasswordDTO;
import com.lanchat.dto.LoginDTO;
import com.lanchat.dto.LoginVO;
import com.lanchat.dto.RegisterDTO;
import com.lanchat.dto.TokenRefreshDTO;
import com.lanchat.entity.DeviceLogin;
import com.lanchat.entity.User;

import java.util.List;

public interface UserService extends IService<User> {

    /** 用户注册 */
    boolean register(RegisterDTO dto);

    /** 用户登录 */
    LoginVO login(LoginDTO dto);

    /** 刷新 Token */
    LoginVO refreshToken(TokenRefreshDTO dto);

    /** 更新在线状态 */
    void updateOnlineStatus(Long userId, Integer online);

    /** 获取在线用户列表 */
    List<User> getOnlineUsers();

    /** 获取用户信息 */
    User getUserInfo(Long userId);

    /** 搜索用户 */
    List<User> searchUsers(String keyword);

    /** 获取用户登录设备列表 */
    List<DeviceLogin> getDevices(Long userId);

    /** 退出指定设备 */
    void logoutDevice(Long userId, Long deviceId);

    /** 获取当前登录设备 */
    DeviceLogin getCurrentDevice(Long userId, String deviceType);

    /** 设置全局免打扰时段 */
    boolean setMutePeriod(Long userId, String muteStart, String muteEnd);

    /** 检查用户当前是否处于免打扰时段 */
    boolean isInMutePeriod(Long userId);

    /** 管理员删除用户（连带清理关联数据） */
    boolean deleteUserByAdmin(Long userId);

    /** 用户修改密码 */
    boolean changePassword(ChangePasswordDTO dto);

    /** 更新用户个人资料（昵称、头像） */
    boolean updateProfile(Long userId, String nickname, String avatar);
}
