package com.example.aicodebackend.service;

import com.example.aicodebackend.model.dto.user.UserQueryRequest;
import com.example.aicodebackend.model.entity.User;
import com.example.aicodebackend.model.vo.LoginUserVO;
import com.example.aicodebackend.model.vo.UserVO;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.core.service.IService;
import jakarta.servlet.http.HttpServletRequest;

import java.util.List;

public interface UserService extends IService<User> {

    long registerUser(String userAccount, String userPassword, String checkPassword);

    String encryptPassword(String password);

    LoginUserVO getLoginUserVO(User user);

    LoginUserVO userLogin(String userAccount, String userPassword, HttpServletRequest request);

    User getLoginUser(HttpServletRequest request);

    boolean userLogout(HttpServletRequest request);

    UserVO getUserVO(User user);

    List<UserVO> getUserVOList(List<User> userList);

    QueryWrapper getQueryWrapper(UserQueryRequest userQueryRequest);

}

