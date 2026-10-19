package com.example.aicodebackend.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import com.example.aicodebackend.constant.UserConstant;
import com.example.aicodebackend.exception.BusinessException;
import com.example.aicodebackend.exception.ErrorCode;
import com.example.aicodebackend.model.dto.user.UserQueryRequest;
import com.example.aicodebackend.model.enums.UserDefaultEnum;
import com.example.aicodebackend.model.enums.UserRoleEnum;
import com.example.aicodebackend.model.entity.table.UserTableDef;
import com.example.aicodebackend.model.vo.LoginUserVO;
import com.example.aicodebackend.model.vo.UserVO;
import com.example.aicodebackend.service.UserService;
import com.mybatisflex.core.query.QueryWrapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;
import com.mybatisflex.spring.service.impl.ServiceImpl;
import com.example.aicodebackend.model.entity.User;
import com.example.aicodebackend.mapper.UserMapper;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements UserService {

    /** 用户注册 */
    @Override
    public long registerUser(String userAccount, String userPassword, String checkPassword) {

        if (StrUtil.hasBlank(userAccount, userPassword, checkPassword)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "参数为空");
        }
        validateAccountAndPassword(userAccount, userPassword);
        if (!userPassword.equals(checkPassword)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "两次输入的密码不一致");
        }

        QueryWrapper queryWrapper = QueryWrapper.create().where(UserTableDef.USER.USER_ACCOUNT.eq(userAccount));
        User user = this.getOne(queryWrapper);
        if (user != null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "用户已存在");
        }

        String md5Password = encryptPassword(userPassword);

        User newUser = new User();
        newUser.setUserAccount(userAccount);
        newUser.setUserPassword(md5Password);
        newUser.setUsername("用户_" + System.currentTimeMillis());
        newUser.setUserAvatar(UserDefaultEnum.AVATAR.getValue());
        newUser.setUserProfile(UserDefaultEnum.PROFILE.getValue());
        newUser.setUserRole(UserRoleEnum.USER.getValue());

        boolean result = this.save(newUser);
        if (!result) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "注册失败!");
        }

        return newUser.getId();
    }

    /** 获取脱敏后的登录用户信息 */
    @Override
    public LoginUserVO getLoginUserVO(User user) {
        if (user == null) {
            return null;
        }
        LoginUserVO loginUserVO = new LoginUserVO();
        BeanUtil.copyProperties(user, loginUserVO);
        return loginUserVO;
    }

    /** 用户登录 */
    @Override
    public LoginUserVO userLogin(String userAccount, String userPassword, HttpServletRequest request) {

        if (StrUtil.hasBlank(userAccount, userPassword)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "参数为空");
        }
        validateAccountAndPassword(userAccount, userPassword);

        String md5Password = encryptPassword(userPassword);

        QueryWrapper queryWrapper = QueryWrapper.create()
                .where(UserTableDef.USER.USER_ACCOUNT.eq(userAccount))
                .and(UserTableDef.USER.USER_PASSWORD.eq(md5Password));
        User user = this.getOne(queryWrapper);
        if (user == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "用户不存在或密码错误");
        }

        request.getSession().setAttribute(UserConstant.USER_LOGIN_STATE, user);

        return getLoginUserVO(user);
    }

    /** 获取当前登录用户 */
    @Override
    public User getLoginUser(HttpServletRequest request) {
        User currentUser = (User) request.getSession().getAttribute(UserConstant.USER_LOGIN_STATE);
        if (currentUser == null || currentUser.getId() == null) {
            throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR);
        }
        // 重新查询数据库，确保用户信息是最新的
        currentUser = this.getById(currentUser.getId());
        if (currentUser == null) {
            throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR);
        }

        return currentUser;
    }

    /** 用户注销 */
    @Override
    public boolean userLogout(HttpServletRequest request) {
        Object user = request.getSession().getAttribute(UserConstant.USER_LOGIN_STATE);
        if (user == null) {
            throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR);
        }

        request.getSession().removeAttribute(UserConstant.USER_LOGIN_STATE);
        return true;
    }

    /** 获取脱敏后的用户信息 */
    @Override
    public UserVO getUserVO(User user) {
        if (user == null) {
            return null;
        }
        UserVO userVO = new UserVO();
        BeanUtil.copyProperties(user, userVO);
        return userVO;
    }

    /** 获取脱敏后的用户信息列表 */
    @Override
    public List<UserVO> getUserVOList(List<User> userList) {

        if (CollUtil.isEmpty(userList)) {
            return Collections.emptyList();
        }

        return userList.stream().map(this::getUserVO).collect(Collectors.toList());
    }

    /** 获取查询条件包装器 */
    @Override
    public QueryWrapper getQueryWrapper(UserQueryRequest userQueryRequest) {

        if (userQueryRequest == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "参数为空");
        }

        Long id = userQueryRequest.getId();
        String username = userQueryRequest.getUsername();
        String userAccount = userQueryRequest.getUserAccount();
        String userProfile = userQueryRequest.getUserProfile();
        String userRole = userQueryRequest.getUserRole();
        String sortField = userQueryRequest.getSortField();
        return QueryWrapper.create()
                .eq("id", id)
                .eq("user_role", userRole)
                .like("user_account", userAccount)
                .like("user_profile", userProfile)
                .like("username", username)
                .orderBy(sortField, userQueryRequest.isAscending());
    }

    /** 密码加密 */
    public String encryptPassword(String password) {
        return DigestUtil.md5Hex(UserDefaultEnum.PASSWORD_SALT.getValue() + password);
    }

    /** 校验账号与密码长度 */
    private void validateAccountAndPassword(String userAccount, String userPassword) {
        if (userAccount.length() < UserConstant.ACCOUNT_MIN_LENGTH
                || userAccount.length() > UserConstant.ACCOUNT_MAX_LENGTH) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR,
                    "用户账号长度必须在" + UserConstant.ACCOUNT_MIN_LENGTH + "到"
                            + UserConstant.ACCOUNT_MAX_LENGTH + "位之间");
        }
        if (userPassword.length() < UserConstant.PASSWORD_MIN_LENGTH
                || userPassword.length() > UserConstant.PASSWORD_MAX_LENGTH) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR,
                    "用户密码长度必须在" + UserConstant.PASSWORD_MIN_LENGTH + "到"
                            + UserConstant.PASSWORD_MAX_LENGTH + "位之间");
        }
    }
}
