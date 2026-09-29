package com.example.aicodebackend.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import com.example.aicodebackend.constant.UserConstant;
import com.example.aicodebackend.exception.BusinessException;
import com.example.aicodebackend.exception.ErrorCode;
import com.example.aicodebackend.model.dto.user.UserQueryRequest;
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

    /**
     * 用户注册
     *
     * @param userAccount   用户账号
     * @param userPassword  用户密码
     * @param checkPassword 确认密码
     *
     * @return 新注册用户的ID
     */
    @Override
    public long registerUser(String userAccount, String userPassword, String checkPassword) {
        // 1. 校验参数
        if (StrUtil.hasBlank(userAccount, userPassword, checkPassword)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "参数为空");
        }
        if (userAccount.length() < 4 || userAccount.length() > 20) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "用户账号长度必须在4到20位之间");
        }
        if (userPassword.length() < 6 || userPassword.length() > 20) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "用户密码长度必须在6到20位之间");
        }
        if (!userPassword.equals(checkPassword)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "两次输入的密码不一致");
        }

        // 2. 查询用户是否已存在
        QueryWrapper queryWrapper = QueryWrapper.create().where(UserTableDef.USER.USER_ACCOUNT.eq(userAccount));
        User user = this.getOne(queryWrapper);
        if (user != null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "用户已存在");
        }

        // 3. 加密密码
        String md5Password = encryptPassword(userPassword);

        // 4. 插入用户数据
        User newUser = new User();
        newUser.setUserAccount(userAccount);
        newUser.setUserPassword(md5Password);
        newUser.setUsername("用户_" + System.currentTimeMillis());
        newUser.setUserAvatar("https://zos.alipayobjects.com/rmsportal/ODTLcjxAfvqbxHnVXCYX.png");
        newUser.setUserProfile("这是一个用户简介。");
        newUser.setUserRole(UserRoleEnum.USER.getValue());

        boolean result = this.save(newUser);
        if (!result) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "注册失败!");
        }

        return newUser.getId();
    }

    /**
     * 获取脱敏后的登录用户信息
     *
     * @param user 用户实体
     *
     * @return 登录用户信息VO
     */
    @Override
    public LoginUserVO getLoginUserVO(User user) {
        if (user == null) {
            return null;
        }
        LoginUserVO loginUserVO = new LoginUserVO();
        BeanUtil.copyProperties(user, loginUserVO);
        return loginUserVO;
    }

    /**
     * 用户登录
     *
     * @param userAccount  用户账号
     * @param userPassword 用户密码
     * @param request      HTTP请求对象
     *
     * @return 登录用户信息VO
     */
    @Override
    public LoginUserVO userLogin(String userAccount, String userPassword, HttpServletRequest request) {
        // 1. 校验参数
        if (StrUtil.hasBlank(userAccount, userPassword)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "参数为空");
        }
        if (userAccount.length() < 4 || userAccount.length() > 20) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "用户账号长度必须在4到20位之间");
        }
        if (userPassword.length() < 6 || userPassword.length() > 20) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "用户密码长度必须在6到20位之间");
        }

        // 2. 密码加密
        String md5Password = encryptPassword(userPassword);
        // 3. 查询用户是否存在
        QueryWrapper queryWrapper = QueryWrapper.create()
                .where(UserTableDef.USER.USER_ACCOUNT.eq(userAccount))
                .and(UserTableDef.USER.USER_PASSWORD.eq(md5Password));
        User user = this.getOne(queryWrapper);
        if (user == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "用户不存在或密码错误");
        }
        // 4. 记录用户登录态
        request.getSession().setAttribute(UserConstant.USER_LOGIN_STATE, user);
        // 5. 返回脱敏后的用户信息
        return getLoginUserVO(user);
    }

    /**
     * 获取当前登录用户
     *
     * @param request
     *
     * @return
     */
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

    /**
     * 用户注销
     *
     * @param request HttpServletRequest对象
     *
     * @return true 注销成功，false 注销失败
     */
    @Override
    public boolean userLogout(HttpServletRequest request) {
        Object user = request.getSession().getAttribute(UserConstant.USER_LOGIN_STATE);
        if (user == null) {
            throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR);
        }

        request.getSession().removeAttribute(UserConstant.USER_LOGIN_STATE);
        return true;
    }

    /**
     * 获取脱敏后的用户信息
     *
     * @param user 用户实体
     *
     * @return 用户信息VO
     */
    @Override
    public UserVO getUserVO(User user) {
        if (user == null) {
            return null;
        }
        UserVO userVO = new UserVO();
        BeanUtil.copyProperties(user, userVO);
        return userVO;
    }

    /**
     * 获取脱敏后的用户信息列表
     *
     * @param userList 用户实体列表
     *
     * @return 用户信息VO列表
     */
    @Override
    public List<UserVO> getUserVOList(List<User> userList) {
        // 1. 校验参数
        if (CollUtil.isEmpty(userList)) {
            return Collections.emptyList();
        }
        // 2. 转换为UserVO列表
        return userList.stream().map(this::getUserVO).collect(Collectors.toList());
    }

    /**
     * 获取查询条件包装器
     *
     * @param userQueryRequest 用户查询请求对象
     *
     * @return 查询条件包装器
     */
    @Override
    public QueryWrapper getQueryWrapper(UserQueryRequest userQueryRequest) {
        // 1. 校验参数
        if (userQueryRequest == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "参数为空");
        }

        // 2. 创建查询条件包装器
        Long id = userQueryRequest.getId();
        String username = userQueryRequest.getUsername();
        String userAccount = userQueryRequest.getUserAccount();
        String userProfile = userQueryRequest.getUserProfile();
        String userRole = userQueryRequest.getUserRole();
        String sortField = userQueryRequest.getSortField();
        String sortOrder = userQueryRequest.getSortOrder();
        return QueryWrapper.create()
                .eq("id", id)
                .eq("user_role", userRole)
                .like("user_account", userAccount)
                .like("user_profile", userProfile)
                .like("username", username)
                .orderBy(sortField, "ascend".equals(sortOrder));
    }

    /**
     * 密码加密
     *
     * @param password 明文密码
     *
     * @return 加密后的密码
     */
    public String encryptPassword(String password) {
        String salt = "ai_code"; // 盐值
        return DigestUtil.md5Hex(salt + password);
    }
}

