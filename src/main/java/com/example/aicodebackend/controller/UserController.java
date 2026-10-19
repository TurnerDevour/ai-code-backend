package com.example.aicodebackend.controller;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.ObjUtil;
import cn.hutool.core.util.StrUtil;
import com.example.aicodebackend.annotation.AuthCheck;
import com.example.aicodebackend.common.BaseResponse;
import com.example.aicodebackend.common.DeleteRequest;
import com.example.aicodebackend.common.PageUtils;
import com.example.aicodebackend.common.ResultUtils;
import com.example.aicodebackend.exception.ErrorCode;
import com.example.aicodebackend.exception.ThrowUtils;
import com.example.aicodebackend.model.dto.user.*;
import com.example.aicodebackend.model.entity.User;
import com.example.aicodebackend.model.enums.UserDefaultEnum;
import com.example.aicodebackend.model.enums.UserRoleEnum;
import com.example.aicodebackend.model.vo.LoginUserVO;
import com.example.aicodebackend.model.vo.UserVO;
import com.example.aicodebackend.service.UserService;
import com.mybatisflex.core.paginate.Page;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/user")
public class UserController {

    @Resource
    private UserService userService;

    /** 用户注册接口 */
    @PostMapping("/register")
    public BaseResponse<Long> userRegister(@RequestBody UserRegisterRequest userRegisterRequest) {

        ThrowUtils.throwIf(userRegisterRequest == null, ErrorCode.PARAMS_ERROR);

        String userAccount = userRegisterRequest.getUserAccount();
        String userPassword = userRegisterRequest.getUserPassword();
        String checkPassword = userRegisterRequest.getCheckPassword();

        long result = userService.registerUser(userAccount, userPassword, checkPassword);
        return ResultUtils.success(result);
    }

    /** 用户登录接口 */
    @PostMapping("/login")
    public BaseResponse<LoginUserVO> userLogin(@RequestBody UserLoginRequest userLoginRequest, HttpServletRequest request) {

        ThrowUtils.throwIf(userLoginRequest == null, ErrorCode.PARAMS_ERROR);

        String userAccount = userLoginRequest.getUserAccount();
        String userPassword = userLoginRequest.getUserPassword();

        LoginUserVO loginUserVO = userService.userLogin(userAccount, userPassword, request);
        return ResultUtils.success(loginUserVO);
    }

    /** 获取当前登录用户信息接口 */
    @GetMapping("/get/login")
    public BaseResponse<LoginUserVO> getCurrentUser(HttpServletRequest request) {

        User loginUser = userService.getLoginUser(request);
        LoginUserVO loginUserVO = userService.getLoginUserVO(loginUser);
        return ResultUtils.success(loginUserVO);
    }

    /** 用户登出接口 */
    @PostMapping("/logout")
    public BaseResponse<Boolean> userLogout(HttpServletRequest request) {

        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR);

        boolean result = userService.userLogout(request);
        return ResultUtils.success(result);
    }

    /** 创建用户信息（仅管理员可用） */
    @PostMapping("/add")
    @AuthCheck(mustRole = UserRoleEnum.ADMIN_VALUE)
    public BaseResponse<Long> addUser(@RequestBody UserAddRequest userAddRequest) {

        ThrowUtils.throwIf(userAddRequest == null, ErrorCode.PARAMS_ERROR);
        ThrowUtils.throwIf(StrUtil.isBlank(userAddRequest.getUserAccount()), ErrorCode.PARAMS_ERROR, "用户账号不能为空");
        ThrowUtils.throwIf(StrUtil.isBlank(userAddRequest.getUsername()), ErrorCode.PARAMS_ERROR, "用户名不能为空");
        ThrowUtils.throwIf(StrUtil.isBlank(userAddRequest.getUserRole()), ErrorCode.PARAMS_ERROR, "用户角色不能为空");

        User user = new User();
        BeanUtil.copyProperties(userAddRequest, user);

        String encryptedPassword = userService.encryptPassword(UserDefaultEnum.PASSWORD.getValue());
        user.setUserPassword(encryptedPassword);

        boolean result = userService.save(user);
        ThrowUtils.throwIf(!result, ErrorCode.SYSTEM_ERROR, "添加用户失败");
        return ResultUtils.success(user.getId());
    }

    /** 更新用户信息（仅管理员可用） */
    @PostMapping("/update")
    @AuthCheck(mustRole = UserRoleEnum.ADMIN_VALUE)
    public BaseResponse<Boolean> updateUser(@RequestBody UserUpdateRequest userUpdateRequest) {

        ThrowUtils.throwIf(userUpdateRequest == null, ErrorCode.PARAMS_ERROR);
        ThrowUtils.throwIf(ObjUtil.isNull(userUpdateRequest.getId()), ErrorCode.PARAMS_ERROR, "用户ID不能为空");
        ThrowUtils.throwIf(StrUtil.isBlank(userUpdateRequest.getUsername()), ErrorCode.PARAMS_ERROR, "用户名不能为空");
        ThrowUtils.throwIf(StrUtil.isBlank(userUpdateRequest.getUserRole()), ErrorCode.PARAMS_ERROR, "用户角色不能为空");

        User user = new User();
        BeanUtil.copyProperties(userUpdateRequest, user);

        boolean result = userService.updateById(user);
        ThrowUtils.throwIf(!result, ErrorCode.SYSTEM_ERROR, "更新用户失败");
        return ResultUtils.success(true);
    }

    /** 删除用户信息（仅管理员可用） */
    @PostMapping("/delete")
    @AuthCheck(mustRole = UserRoleEnum.ADMIN_VALUE)
    public BaseResponse<Boolean> deleteUser(@RequestBody DeleteRequest deleteRequest) {

        ThrowUtils.throwIf(deleteRequest == null || deleteRequest.getId() == null, ErrorCode.PARAMS_ERROR);

        boolean result = userService.removeById(deleteRequest.getId());
        ThrowUtils.throwIf(!result, ErrorCode.SYSTEM_ERROR, "删除用户失败");
        return ResultUtils.success(true);
    }

    /** 分页获取用户信息列表（仅管理员可用） */
    @PostMapping("/list/page/vo")
    @AuthCheck(mustRole = UserRoleEnum.ADMIN_VALUE)
    public BaseResponse<Page<UserVO>> listUserVOByPage(@RequestBody UserQueryRequest userQueryRequest) {

        ThrowUtils.throwIf(userQueryRequest == null, ErrorCode.PARAMS_ERROR, "查询参数为空");

        long pageNum = userQueryRequest.getPageNum();
        long pageSize = userQueryRequest.getPageSize();
        PageUtils.validate(pageNum, pageSize);
        Page<User> userPage = userService.page(Page.of(pageNum, pageSize), userService.getQueryWrapper(userQueryRequest));
        return ResultUtils.success(PageUtils.toVoPage(userPage, pageNum, pageSize, userService::getUserVOList));
    }

    /** 根据ID获取用户信息（仅管理员可用） */
    @GetMapping("/get")
    @AuthCheck(mustRole = UserRoleEnum.ADMIN_VALUE)
    public BaseResponse<User> getUserById(@RequestParam Long id) {

        ThrowUtils.throwIfInvalidId(id, "用户ID");

        User user = userService.getById(id);
        ThrowUtils.throwIf(user == null, ErrorCode.NOT_FOUND_ERROR, "用户不存在");
        return ResultUtils.success(user);
    }

    /** 根据ID获取用户信息VO */
    @GetMapping("/get/vo")
    public BaseResponse<UserVO> getUserVOById(@RequestParam Long id) {

        ThrowUtils.throwIfInvalidId(id, "用户ID");

        User user = userService.getById(id);
        ThrowUtils.throwIf(user == null, ErrorCode.NOT_FOUND_ERROR, "用户不存在");

        UserVO userVO = userService.getUserVO(user);
        return ResultUtils.success(userVO);
    }
}
