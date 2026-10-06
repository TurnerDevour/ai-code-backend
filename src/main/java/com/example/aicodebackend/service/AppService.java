package com.example.aicodebackend.service;

import com.example.aicodebackend.model.dto.app.AppQueryRequest;
import com.example.aicodebackend.model.entity.App;
import com.example.aicodebackend.model.entity.User;
import com.example.aicodebackend.model.vo.AppVO;
import com.example.aicodebackend.model.vo.DeployStatusVO;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.core.service.IService;
import reactor.core.publisher.Flux;

import java.util.List;

public interface AppService extends IService<App> {

    AppVO getAppVO(App app);

    List<AppVO> getAppVOList(List<App> appList);

    QueryWrapper getQueryWrapper(AppQueryRequest appQueryRequest);

    /**
     * 删除应用，并关联删除该应用的所有对话历史
     *
     * @param appId 应用id
     *
     * @return 是否删除成功
     */
    boolean deleteApp(Long appId);

    Flux<String> chatToGenCode(Long appId, String prompt, User LoginUser);

    /**
     * 同步部署（一次请求内完成构建与发布，返回部署地址）
     *
     * @param appId     应用id
     * @param loginUser 当前登录用户
     *
     * @return 部署地址
     */
    String deployApp(Long appId, User loginUser);

    /**
     * 查询部署状态（异步部署轮询用）
     *
     * @param appId     应用id
     * @param loginUser 当前登录用户
     *
     * @return 部署状态
     */
    DeployStatusVO getDeployStatus(Long appId, User loginUser);

    /**
     * 提交异步部署任务（立即返回，后台构建）
     *
     * @param appId     应用id
     * @param loginUser 当前登录用户
     *
     * @return 提交后的部署状态
     */
    DeployStatusVO submitDeploy(Long appId, User loginUser);
}
