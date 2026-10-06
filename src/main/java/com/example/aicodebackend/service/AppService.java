package com.example.aicodebackend.service;

import com.example.aicodebackend.core.generation.GenerationTaskRegistry;
import com.example.aicodebackend.model.dto.app.AppQueryRequest;
import com.example.aicodebackend.model.entity.App;
import com.example.aicodebackend.model.entity.User;
import com.example.aicodebackend.model.vo.AppVO;
import com.example.aicodebackend.model.vo.DeployStatusVO;
import com.example.aicodebackend.model.vo.GenerationStatusVO;
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

    Flux<GenerationTaskRegistry.SequencedFrame> chatToGenCode(Long appId, String prompt, User LoginUser);

    /**
     * 续订当前应用的生成流（方案 C：生成在服务端独立运行，客户端断开后可重新接上）
     *
     * @param appId     应用id
     * @param fromSeq   已收到的最后一帧序号（补发它之后的帧）；传负数表示改用 subId 定位
     * @param subId     首次连接的订阅标识（fromSeq 为负数时按它定位）
     * @param loginUser 当前登录用户
     *
     * @return 续订的生成流
     */
    Flux<GenerationTaskRegistry.SequencedFrame> resumeGenCode(Long appId, long fromSeq, String subId, User loginUser);

    /**
     * 查询当前生成任务状态（方案 C）
     *
     * @param appId     应用id
     * @param loginUser 当前登录用户
     *
     * @return 生成状态
     */
    GenerationStatusVO getGenStatus(Long appId, User loginUser);

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
