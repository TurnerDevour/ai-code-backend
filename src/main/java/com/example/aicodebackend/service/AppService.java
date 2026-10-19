package com.example.aicodebackend.service;

import com.example.aicodebackend.core.builder.DeployQueueManager;
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

    /** 删除应用，并关联删除该应用的所有对话历史 */
    boolean deleteApp(Long appId);

    Flux<GenerationTaskRegistry.SequencedFrame> chatToGenCode(Long appId, String prompt, User LoginUser);

    /** 续订当前应用的生成流（方案 C：生成在服务端独立运行，客户端断开后可重新接上） */
    Flux<GenerationTaskRegistry.SequencedFrame> resumeGenCode(Long appId, long fromSeq, String subId, User loginUser);

    /** 查询当前生成任务状态（方案 C） */
    GenerationStatusVO getGenStatus(Long appId, User loginUser);

    /** 同步部署（一次请求内完成构建与发布，返回部署地址） */
    String deployApp(Long appId, User loginUser);

    /** 查询部署状态（异步部署轮询用） */
    DeployStatusVO getDeployStatus(Long appId, User loginUser);

    /** 提交异步部署任务（立即返回，后台构建） */
    DeployStatusVO submitDeploy(Long appId, User loginUser);

    /**
     * 消费一条部署任务（由 RabbitMQ 消费者调用，也可在测试/补跑时直接调用）
     * 内部用条件更新抢占 queued -&gt; deploying，重复投递不会重复构建。
     * 业务失败会落库为 failed 并正常返回（消费者 ACK）；基础设施异常会尽量把状态退回 queued……
     */
    void handleDeployTask(DeployQueueManager.DeployTask task);

    /**
     * 处理进入死信队列的部署任务：把对应应用落为 failed，让用户可以立即重新提交
     * 只在该任务仍持有应用状态时更新（按 taskId CAS），不会覆盖用户已经重新提交的新任务。
     */
    void handleDeadLetter(DeployQueueManager.DeployTask task);
}
