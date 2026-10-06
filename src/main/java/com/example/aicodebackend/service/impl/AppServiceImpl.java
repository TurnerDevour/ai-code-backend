package com.example.aicodebackend.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.exceptions.ExceptionUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.example.aicodebackend.ai.model.message.ErrorMessage;
import com.example.aicodebackend.ai.model.message.StreamMessageTypeEnum;
import com.example.aicodebackend.constant.AppConstant;
import com.example.aicodebackend.core.AiCodeGeneratorFacade;
import com.example.aicodebackend.core.builder.DeployQueueManager;
import com.example.aicodebackend.core.builder.VueProjectBuilder;
import com.example.aicodebackend.core.generation.GenerationTaskRegistry;
import com.example.aicodebackend.core.handler.StreamHandlerExecutor;
import com.example.aicodebackend.exception.BusinessException;
import com.example.aicodebackend.exception.ErrorCode;
import com.example.aicodebackend.exception.ThrowUtils;
import com.example.aicodebackend.mapper.AppMapper;
import com.example.aicodebackend.model.dto.app.AppQueryRequest;
import com.example.aicodebackend.model.entity.App;
import com.example.aicodebackend.model.entity.User;
import com.example.aicodebackend.model.enums.AIModelTypeEnum;
import com.example.aicodebackend.model.enums.ChatMessageTypeEnum;
import com.example.aicodebackend.model.enums.CodeGenTypeEnum;
import com.example.aicodebackend.model.enums.DeployStatusEnum;
import com.example.aicodebackend.model.vo.AppVO;
import com.example.aicodebackend.model.vo.DeployStatusVO;
import com.example.aicodebackend.model.vo.GenerationStatusVO;
import com.example.aicodebackend.model.vo.UserVO;
import com.example.aicodebackend.service.AppService;
import com.example.aicodebackend.service.ChatHistoryService;
import com.example.aicodebackend.service.ScreenshotService;
import com.example.aicodebackend.service.UserService;
import com.mybatisflex.core.query.QueryColumn;
import com.mybatisflex.core.query.QueryCondition;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.spring.service.impl.ServiceImpl;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

@Slf4j
@Service
public class AppServiceImpl extends ServiceImpl<AppMapper, App> implements AppService {

    @Resource
    private UserService userService;

    @Resource
    private AiCodeGeneratorFacade aiCodeGeneratorFacade;

    @Resource
    private ChatHistoryService chatHistoryService;

    @Resource
    private StreamHandlerExecutor streamHandlerExecutor;

    @Resource
    private VueProjectBuilder vueProjectBuilder;

    @Resource
    private ScreenshotService screenshotService;

    @Resource
    private DeployQueueManager deployQueueManager;

    @Resource
    private GenerationTaskRegistry generationTaskRegistry;

    /**
     * 应用 id -> 对话生成信号量（容量 1）
     * <p>
     * 同一个应用的并发对话请求会共用同一个 {@code MessageWindowChatMemory} 实例（见 AiCodeGeneratorServiceFactory），
     * 而该记忆实例与其 Redis 存储都没有并发保护：并发写会互相覆盖，导致对话记忆丢更新
     * （实测 4 路并发：数据库 12 条消息，记忆只剩 7 条）。这里按 appId 串行化生成过程，
     * 用一个明确的错误提示替代静默的记忆损坏。
     * <p>
     * 为什么用 {@link Semaphore} 而不是 {@link ReentrantLock}：
     * 释放动作发生在 Reactor 的 {@code doFinally} 里，而响应式流的终止/取消可能发生在<b>另一个线程</b>上，
     * ReentrantLock 只允许持有线程解锁，跨线程释放会抛 {@link IllegalMonitorStateException} 并导致锁永久泄漏
     * （实测：一次客户端取消后，该应用的后续请求会一直等到超时才失败）。Semaphore 不绑定线程，天然支持这种场景。
     * <p>
     * 注意：单实例内有效；多实例部署需要换成 Redis 分布式锁（同 key、带租约与续期）。
     */
    private static final Map<Long, Semaphore> CHAT_SEMAPHORES = new ConcurrentHashMap<>();

    /**
     * 应用 id -> 部署执行锁：保证同一个应用的"构建 + 复制到部署目录"串行执行，
     * 避免两个部署同时写同一个 dist / 同一个部署目录。
     */
    private static final Map<Long, ReentrantLock> DEPLOY_LOCKS = new ConcurrentHashMap<>();

    /**
     * 应用 id -> 部署提交检查锁：异步部署提交时在锁内完成"读状态 -> 落 deploying"，
     * 保证并发提交只有一个被受理。
     * <p>
     * 必须与 {@link #DEPLOY_LOCKS} 分开：提交检查要快速返回（否则并发提交会一直等到构建结束），
     * 而部署执行锁会持有整个构建过程（最长 npm install 超时 10 分钟），
     * 两者若共用，轮询/重复提交都会被构建阻塞。
     */
    private static final Map<Long, ReentrantLock> DEPLOY_SUBMIT_CHECKS = new ConcurrentHashMap<>();

    /**
     * 对话生成锁的最长等待时间（秒）
     */
    private static final long CHAT_LOCK_WAIT_SECONDS = 300;

    /**
     * 部署 deployKey 落库的最大重试次数（6 位随机串空间较小，理论上可能与历史 key 撞车）
     */
    private static final int DEPLOY_KEY_MAX_ATTEMPTS = 5;

    /**
     * "部署中"状态被视为僵死（可重新提交）的分钟数
     * <p>
     * 覆盖两类情况：进程在部署过程中重启、构建任务异常退出没来得及回写状态。
     * 正常构建（含 npm install）远小于该阈值。
     */
    private static final long DEPLOY_STALE_MINUTES = 30;

    /**
     * 落库的部署失败原因最大长度
     */
    private static final int DEPLOY_ERROR_MAX_LENGTH = 500;

    /**
     * 同步部署接口等待构建额度的最长时间（秒）
     * <p>
     * 同步接口既要当场返回地址、又不能绕过并发限制，因此这里给它一个等待窗口：
     * 超过该时间说明构建队列积压严重，直接提示改用异步接口，避免请求被长时间挂住。
     */
    private static final long DEPLOY_SYNC_PERMIT_TIMEOUT_SECONDS = 300;

    /**
     * Vue 工程构建的暂存目录前缀：构建发生在暂存目录里，成功后再原子切换进源码目录
     */
    private static final String BUILD_STAGING_PREFIX = ".build-staging-";

    /**
     * 获取脱敏后的应用信息
     *
     * @param app 应用实体
     *
     * @return 应用信息VO
     */
    @Override
    public AppVO getAppVO(App app) {
        if (app == null) {
            return null;
        }
        AppVO appVO = new AppVO();
        BeanUtil.copyProperties(app, appVO);
        // 关联查询创建用户信息
        Long userId = app.getUserId();
        if (userId != null) {
            User user = userService.getById(userId);
            appVO.setUser(userService.getUserVO(user));
        }
        return appVO;
    }

    /**
     * 获取脱敏后的应用信息列表
     *
     * @param appList 应用实体列表
     *
     * @return 应用信息VO列表
     */
    @Override
    public List<AppVO> getAppVOList(List<App> appList) {
        // 1. 校验参数
        if (CollUtil.isEmpty(appList)) {
            return Collections.emptyList();
        }
        // 2. 批量获取用户信息，避免 N+1 查询问题
        Set<Long> userIds = appList.stream()
                .map(App::getUserId)
                .collect(Collectors.toSet());
        Map<Long, UserVO> userVOMap = userService.listByIds(userIds).stream()
                .collect(Collectors.toMap(User::getId, userService::getUserVO));
        // 3. 构建应用信息VO列表
        return appList.stream()
                .map(app -> {
                    AppVO appVO = new AppVO();
                    BeanUtil.copyProperties(app, appVO);
                    Long userId = app.getUserId();
                    if (userId != null) {
                        appVO.setUser(userVOMap.get(userId));
                    }
                    return appVO;
                })
                .collect(Collectors.toList());
    }

    /**
     * 获取查询条件包装器
     *
     * @param appQueryRequest 应用查询请求对象
     *
     * @return 查询条件包装器
     */
    @Override
    public QueryWrapper getQueryWrapper(AppQueryRequest appQueryRequest) {
        // 1. 校验参数
        if (appQueryRequest == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "参数为空");
        }

        // 2. 创建查询条件包装器（支持除时间外的任何字段查询）
        Long id = appQueryRequest.getId();
        String appName = appQueryRequest.getAppName();
        String cover = appQueryRequest.getCover();
        String initPrompt = appQueryRequest.getInitPrompt();
        String codeGenType = appQueryRequest.getCodeGenType();
        String deployKey = appQueryRequest.getDeployKey();
        Integer priority = appQueryRequest.getPriority();
        Long userId = appQueryRequest.getUserId();
        String sortField = appQueryRequest.getSortField();
        String sortOrder = appQueryRequest.getSortOrder();
        QueryWrapper queryWrapper = QueryWrapper.create()
                .eq("id", id)
                .eq("priority", priority)
                .eq("user_id", userId)
                .like("app_name", appName)
                .like("cover", cover)
                .like("init_prompt", initPrompt)
                .like("code_gen_type", codeGenType)
                .like("deploy_key", deployKey);

        // 3. 排序字段为空时，默认按创建时间降序，保证分页结果稳定
        if (StrUtil.isNotBlank(sortField)) {
            queryWrapper.orderBy(sortField, "ascend".equals(sortOrder));
        } else {
            queryWrapper.orderBy("create_time", false);
        }
        return queryWrapper;
    }

    /**
     * 根据应用ID和提示生成代码，并以流式方式返回生成的代码。
     *
     * @param appId     应用ID
     * @param prompt    用户提供的提示，用于指导代码生成。
     * @param loginUser 当前登录用户
     *
     * @return 生成的代码流
     */
    @Override
    public Flux<GenerationTaskRegistry.SequencedFrame> chatToGenCode(Long appId, String prompt, User loginUser) {
        // 1. 校验参数
        ThrowUtils.throwIf(appId == null, ErrorCode.PARAMS_ERROR, "应用ID为空");
        ThrowUtils.throwIf(StrUtil.isBlank(prompt), ErrorCode.PARAMS_ERROR, "提示为空");

        // 2. 获取应用信息
        App app = this.getById(appId);
        if (app == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "应用不存在");
        }

        // 3. 验证用户是否有权限访问该应用，仅允许应用的创建者访问
        if (!app.getUserId().equals(loginUser.getId())) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "无权限访问该应用");
        }

        // 4.获取应用生成类型
        String codeGenType = app.getCodeGenType();
        CodeGenTypeEnum codeGenTypeEnum = CodeGenTypeEnum.getEnumByValue(codeGenType);
        if (codeGenTypeEnum == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "不支持的代码生成类型");
        }

        // 4.1 获取应用所选 AI 模型类型（历史数据可能为空，为空时由工厂回落到默认模型）
        AIModelTypeEnum aiModelTypeEnum = AIModelTypeEnum.getEnumByValue(app.getAiModelType());

        // 5. 同一个应用同一时刻只允许一个生成请求：
        //    应用级对话记忆是共享的、无并发保护，同时进入会让记忆互相覆盖（丢上下文），
        //    因此这里等待（而不是立刻失败），等待超时后再给出明确提示，而不是静默损坏记忆。
        Semaphore chatSemaphore = CHAT_SEMAPHORES.computeIfAbsent(appId, key -> new Semaphore(1));
        boolean locked;
        try {
            locked = chatSemaphore.tryAcquire(CHAT_LOCK_WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "生成请求等待被中断，请重试");
        }
        if (!locked) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "该应用正在生成中，请等待上一轮生成结束");
        }
        try {
            // 6. 保存用户消息（用户发送消息时立即持久化）
            chatHistoryService.addChatMessage(appId, loginUser.getId(), prompt, ChatMessageTypeEnum.USER);

            // 6.1 链条分流：
            //     VUE_PROJECT（多轮工具调用、耗时最长）走"生成任务注册表"——生成在服务端独立运行，
            //     客户端断开只解绑自己的订阅，历史里拿到的是完整内容（方案 C）；
            //     其它类型沿用原来的流处理（本次未改）。
            if (codeGenTypeEnum == CodeGenTypeEnum.VUE_PROJECT) {
                Flux<GenerationTaskRegistry.SequencedFrame> registryStream =
                        chatToGenCodeWithRegistry(app, appId, prompt, loginUser, codeGenTypeEnum, aiModelTypeEnum);
                return registryStream.doFinally(signalType -> chatSemaphore.release());
            }

            // 7. 调用 AiCodeGeneratorFacade 生成代码并返回流式输出，同时持久化 AI 消息和错误信息
            Flux<String> codeStream = aiCodeGeneratorFacade.generateAndSaveCodeStream(prompt, codeGenTypeEnum, appId, aiModelTypeEnum);

            // 8. 收集AI响应内容并再完成后记录到对话历史
            Flux<String> handledStream = streamHandlerExecutor.doExecute(codeStream, chatHistoryService, appId, loginUser, codeGenTypeEnum);

            // 9. 无论正常结束、出错还是被取消，都要释放信号量，并保证客户端感知到流已结束。
            //    这里只做"释放 + 兜底下发一个结束空帧"，不改变正常消息类型（HTML/MULTI_FILE 仍是纯文本增量）。
            //    HTML / MULTI_FILE 没有"续订"概念，序号按到达顺序递增（客户端不使用它）。
            java.util.concurrent.atomic.AtomicLong htmlSeq = new java.util.concurrent.atomic.AtomicLong();
            return handledStream
                    .doFinally(signalType -> chatSemaphore.release())
                    .concatWith(Flux.just(""))
                    .onErrorResume(error -> {
                        if (isClientDisconnected(error)) {
                            log.warn("客户端已断开，生成流提前结束，appId: {}", appId);
                            return Flux.empty();
                        }
                        log.error("生成代码流式响应失败，appId: {}", appId, error);
                        return Flux.just(JSONUtil.toJsonStr(new ErrorMessage("代码生成失败，请稍后重试")));
                    })
                    .map(chunk -> new GenerationTaskRegistry.SequencedFrame(htmlSeq.incrementAndGet(), chunk));
        } catch (RuntimeException e) {
            chatSemaphore.release();
            throw e;
        }
    }

    /**
     * VUE_PROJECT：走"生成任务注册表"的新链路（方案 C）
     * <p>
     * 生成在服务端独立运行（累积 + 结束后落库完整内容），返回给客户端的只是"订阅这份输出"；
     * 客户端断开只解绑自己的订阅，不会取消生成，也不会让历史里只有半截内容。
     *
     * @param app              应用实体
     * @param appId            应用ID
     * @param prompt           提示词
     * @param loginUser        登录用户
     * @param codeGenTypeEnum  代码生成类型（调用方已保证是 VUE_PROJECT）
     * @param aiModelTypeEnum  AI 模型类型
     *
     * @return 下发给客户端的流（每帧一个 JSON 消息）
     */
    private Flux<GenerationTaskRegistry.SequencedFrame> chatToGenCodeWithRegistry(App app,
                                                   Long appId,
                                                   String prompt,
                                                   User loginUser,
                                                   CodeGenTypeEnum codeGenTypeEnum,
                                                   AIModelTypeEnum aiModelTypeEnum) {
        String subId = "sub-" + appId + "-" + System.nanoTime();
        GenerationTaskRegistry.GenerationTask task = generationTaskRegistry.submitOrGet(appId, loginUser,
                () -> buildRegistryUpstream(app, appId, prompt, codeGenTypeEnum, aiModelTypeEnum));
        return generationTaskRegistry.subscribe(task, subId, 0L);
    }

    /**
     * 构建注册表用的上游流：把生成结果拆成"下发帧"与"累积文本"
     * <p>
     * 下发帧与原来的 SSE 格式保持一致（复用 StreamMessageTypeEnum 的 JSON 结构），
     * 累积文本用于落库：文本增量取原文，工具执行结果带上完整入参，保证「查看对话」能看到完整历史。
     *
     * @param app             应用实体
     * @param appId           应用ID
     * @param prompt          提示词
     * @param codeGenTypeEnum 代码生成类型
     * @param aiModelTypeEnum AI 模型类型
     *
     * @return 上游输出流
     */
    private Flux<GenerationTaskRegistry.GenerationEmit> buildRegistryUpstream(App app,
                                                                             Long appId,
                                                                             String prompt,
                                                                             CodeGenTypeEnum codeGenTypeEnum,
                                                                             AIModelTypeEnum aiModelTypeEnum) {
        // 复用既有的流式生成链路（内部已按 VUE_PROJECT 处理思考内容、工具请求与工具执行结果），
        // 这里只负责把它拆成"下发帧 + 累积文本"
        return aiCodeGeneratorFacade.generateAndSaveCodeStream(prompt, codeGenTypeEnum, appId, aiModelTypeEnum)
                .map(this::toRegistryEmit)
                .filter(emit -> StrUtil.isNotEmpty(emit.sseChunk()) || StrUtil.isNotEmpty(emit.historyChunk()));
    }

    /**
     * 把一条流式消息转换成"下发帧 + 累积文本"
     *
     * @param messageJson 流式消息的 JSON
     *
     * @return 产出
     */
    private GenerationTaskRegistry.GenerationEmit toRegistryEmit(String messageJson) {
        if (StrUtil.isBlank(messageJson)) {
            return new GenerationTaskRegistry.GenerationEmit("", "");
        }
        return new GenerationTaskRegistry.GenerationEmit(messageJson, extractHistoryContent(messageJson));
    }

    /**
     * 从流式消息里取出需要累积进对话历史的内容
     *
     * @param messageJson 流式消息的 JSON
     *
     * @return 需要累积的文本，不需要累积时返回空串
     */
    private String extractHistoryContent(String messageJson) {
        try {
            JSONObject json = JSONUtil.parseObj(messageJson);
            String type = json.getStr("type");
            if (StreamMessageTypeEnum.AI_RESPONSE.getValue().equals(type)) {
                return StrUtil.nullToEmpty(json.getStr("data"));
            }
            if (StreamMessageTypeEnum.TOOL_EXECUTED.getValue().equals(type)) {
                // 工具执行结果：记录"工具名 + 完整入参"，保证历史里能看到写了哪些文件与内容
                String name = StrUtil.nullToEmpty(json.getStr("name"));
                String arguments = StrUtil.nullToEmpty(json.getStr("arguments"));
                return "\n[工具调用] " + name + " " + arguments + "\n";
            }
        } catch (Exception e) {
            log.warn("解析流式消息失败（不影响下发）：{}", e.getMessage());
        }
        return "";
    }

    /**
     * 续订当前应用的生成流（方案 C）
     *
     * @param appId     应用ID
     * @param fromSeq   已收到的最后一帧序号
     * @param loginUser 登录用户
     *
     * @return 续订的流
     */
    @Override
    public Flux<GenerationTaskRegistry.SequencedFrame> resumeGenCode(Long appId, long fromSeq, String subId, User loginUser) {
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR, "应用ID不合法");
        ThrowUtils.throwIf(loginUser == null, ErrorCode.PARAMS_ERROR, "用户未登录");
        App app = this.getById(appId);
        ThrowUtils.throwIf(app == null, ErrorCode.NOT_FOUND_ERROR, "应用不存在");
        ThrowUtils.throwIf(!app.getUserId().equals(loginUser.getId()), ErrorCode.NO_AUTH_ERROR, "无权限访问该应用");
        if (CodeGenTypeEnum.VUE_PROJECT != CodeGenTypeEnum.getEnumByValue(app.getCodeGenType())) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "该应用类型不支持续订生成流");
        }
        GenerationTaskRegistry.GenerationTask task = generationTaskRegistry.find(appId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND_ERROR, "当前没有可续订的生成任务"));
        // 起点定位：优先用客户端回传的帧序号（精确）；没有序号时退回"服务端记录的该订阅已下发进度"
        long effectiveFromSeq = fromSeq;
        if (effectiveFromSeq < 0) {
            effectiveFromSeq = StrUtil.isBlank(subId) ? 0L : generationTaskRegistry.lastSentSeq(task, subId);
        }
        String newSubId = "resume-" + appId + "-" + System.nanoTime();
        log.info("续订生成流：appId={}, fromSeq={}, subId={}, 有效起点={}, 任务状态={}",
                appId, fromSeq, subId, effectiveFromSeq, task.getStatus());
        return generationTaskRegistry.subscribe(task, newSubId, effectiveFromSeq);
    }

    /**
     * 查询当前生成任务状态（方案 C）
     *
     * @param appId     应用ID
     * @param loginUser 登录用户
     *
     * @return 生成状态
     */
    @Override
    public GenerationStatusVO getGenStatus(Long appId, User loginUser) {
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR, "应用ID不合法");
        ThrowUtils.throwIf(loginUser == null, ErrorCode.PARAMS_ERROR, "用户未登录");
        App app = this.getById(appId);
        ThrowUtils.throwIf(app == null, ErrorCode.NOT_FOUND_ERROR, "应用不存在");
        ThrowUtils.throwIf(!app.getUserId().equals(loginUser.getId()), ErrorCode.NO_AUTH_ERROR, "无权限访问该应用");
        return generationTaskRegistry.find(appId)
                .map(task -> {
                    GenerationStatusVO vo = new GenerationStatusVO();
                    vo.setAppId(appId);
                    vo.setStatus(task.getStatus().getValue());
                    vo.setRunning(task.isRunning());
                    vo.setContentLength(task.contentLength());
                    vo.setLastSeq(task.getFrameSeq());
                    vo.setErrorMessage(task.getErrorMessage());
                    vo.setMessage(switch (task.getStatus()) {
                        case RUNNING -> "正在生成中（服务端独立运行，可随时断开或续订）";
                        case FINISHED -> "生成已完成，完整内容已写入对话历史";
                        case FAILED -> "生成失败，可重新发起";
                    });
                    return vo;
                })
                .orElseGet(() -> GenerationStatusVO.none(appId));
    }

    /**
     * 判断异常是否由客户端主动断开引起（如用户关闭页面、网关超时）
     * <p>
     * 这类异常不是业务失败：SSE 响应已经提交，再回写任何内容都没有意义，
     * 继续按异常上报只会在日志里制造噪声，因此单独识别出来降级为 warn。
     *
     * @param error 流内异常
     *
     * @return true 表示客户端已断开
     */
    private boolean isClientDisconnected(Throwable error) {
        Throwable current = error;
        while (current != null) {
            String name = current.getClass().getName();
            if (name.contains("ClientAbortException")
                    || name.contains("AsyncRequestNotUsableException")
                    || name.contains("EofException")
                    || current instanceof IOException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    /**
     * 部署应用
     * <p>
     * 并发语义（本次修复的核心）：
     * 1. deployKey 的"生成 + 落库"是一个 CAS 过程（{@code UPDATE app SET deploy_key=? WHERE id=? AND deploy_key IS NULL}），
     * 只有一个请求能成为 key 的拥有者，其余并发请求复用它，因此同一个应用永远只有一个部署目录、一个对外地址；
     * 2. 部署过程按 appId 加锁，构建与文件复制不会互相踩踏；
     * 3. 构建成功后才提交 deployKey；构建/复制失败则把 key 回滚为 NULL，允许下一次重试复用同一条路径。
     *
     * @param appId     应用ID
     * @param loginUser 当前登录用户
     *
     * @return 可访问的部署地址
     */
    @Override
    public String deployApp(Long appId, User loginUser) {
        // 1. 校验参数
        ThrowUtils.throwIf(appId == null, ErrorCode.PARAMS_ERROR, "应用ID为空");
        ThrowUtils.throwIf(loginUser == null, ErrorCode.PARAMS_ERROR, "用户未登录");

        // 2. 获取应用信息并校验归属
        App app = this.getById(appId);
        ThrowUtils.throwIf(app == null, ErrorCode.NOT_FOUND_ERROR, "应用不存在");
        ThrowUtils.throwIf(!app.getUserId().equals(loginUser.getId()), ErrorCode.NO_AUTH_ERROR, "无权限访问该应用");

        // 3. 源码目录必须存在
        String codeGenType = app.getCodeGenType();
        File sourceDir = new File(buildSourceDirPath(codeGenType, appId));
        if (!sourceDir.exists() || !sourceDir.isDirectory()) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "源代码目录不存在，请先生成代码");
        }

        // 4. 同一个应用的部署串行执行：避免构建/复制互相覆盖，也避免两个请求各写一份部署目录
        ReentrantLock deployLock = DEPLOY_LOCKS.computeIfAbsent(appId, key -> new ReentrantLock());
        deployLock.lock();
        try {
            // 5. 构建必须走统一的限流闸门：同步接口也不能例外，
            //    否则 100 个同步部署请求依然会拉起 100 个 npm 进程（实测部署成功率掉到 46%）
            return deployQueueManager.runWithPermit(() -> doDeploy(app, appId, codeGenType, sourceDir),
                    Duration.ofSeconds(DEPLOY_SYNC_PERMIT_TIMEOUT_SECONDS));
        } catch (BusinessException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "部署等待被中断，请重试");
        } finally {
            deployLock.unlock();
        }
    }

    /**
     * 查询部署状态（异步部署的轮询接口）
     * <p>
     * 状态来源优先级：deploy_status 字段 > deployKey 是否存在。
     * 改造前的历史数据没有 deploy_status，只要已经有 deployKey 就视为部署完成，前端无需区分。
     *
     * @param appId     应用ID
     * @param loginUser 当前登录用户
     *
     * @return 部署状态视图
     */
    @Override
    public DeployStatusVO getDeployStatus(Long appId, User loginUser) {
        ThrowUtils.throwIf(appId == null, ErrorCode.PARAMS_ERROR, "应用ID为空");
        ThrowUtils.throwIf(loginUser == null, ErrorCode.PARAMS_ERROR, "用户未登录");
        App app = this.getById(appId);
        ThrowUtils.throwIf(app == null, ErrorCode.NOT_FOUND_ERROR, "应用不存在");
        ThrowUtils.throwIf(!isDeployOperator(app, loginUser), ErrorCode.NO_AUTH_ERROR, "无权限访问该应用");
        return buildDeployStatus(app);
    }

    /**
     * 提交异步部署任务：立即返回，后台线程完成构建与发布
     * <p>
     * 与同步接口的差异：
     * <ul>
     *     <li>不在请求线程里执行 npm 构建，HTTP 请求快速返回，长耗时构建不会占满 Web 线程、也不会把网关/浏览器拖到超时；</li>
     *     <li>用 deploy_status 的条件更新（CAS）保证同一个应用同时只有一个部署任务：idle/failed/deploying(超时) -> deploying，
     *     抢不到的请求直接返回当前状态，不会重复构建、不会写第二份部署目录；</li>
     *     <li>失败时落 deploy_error 并把状态放回 failed，前端可再次提交重试。</li>
     * </ul>
     *
     * @param appId     应用ID
     * @param loginUser 当前登录用户
     *
     * @return 当前部署状态（deploying 表示已受理）
     */
    @Override
    public DeployStatusVO submitDeploy(Long appId, User loginUser) {
        ThrowUtils.throwIf(appId == null, ErrorCode.PARAMS_ERROR, "应用ID为空");
        ThrowUtils.throwIf(loginUser == null, ErrorCode.PARAMS_ERROR, "用户未登录");

        App app = this.getById(appId);
        ThrowUtils.throwIf(app == null, ErrorCode.NOT_FOUND_ERROR, "应用不存在");
        ThrowUtils.throwIf(!isDeployOperator(app, loginUser), ErrorCode.NO_AUTH_ERROR, "无权限访问该应用");

        String sourceDirPath = buildSourceDirPath(app.getCodeGenType(), appId);
        File sourceDir = new File(sourceDirPath);
        if (!sourceDir.exists() || !sourceDir.isDirectory()) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "源代码目录不存在，请先生成代码");
        }

        // 抢占部署资格：在 per-app 锁内完成"检查 + 落状态"，
        // 避免依赖 QueryCondition 对象拼 OR/IS NULL（实测该形式下条件没有进 WHERE，8 次并发提交全部被受理）。
        // 单实例内由这把锁保证互斥；多实例部署时把这里换成带租约的 Redis 分布式锁即可。
        ReentrantLock submitLock = DEPLOY_SUBMIT_CHECKS.computeIfAbsent(appId, key -> new ReentrantLock());
        long sequence;
        submitLock.lock();
        try {
            App latest = this.getById(appId);
            if (latest == null) {
                throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "应用不存在");
            }
            if (!isDeploySubmittable(latest)) {
                log.info("部署任务未被受理（已有部署在进行或刚刚完成），appId: {}, 当前状态: {}", appId, latest.getDeployStatus());
                // 明确告诉调用方"这次没有被受理"，并带上当前真实状态，
                // 避免前端把"部署中"误判成自己这次提交成功、或把"已完成"当成新任务在跑
                return DeployStatusVO.rejected(appId, DeployStatusEnum.getEnumByValue(latest.getDeployStatus()),
                        "部署任务未被受理：当前状态为 " + describeDeployStatus(latest) + "，未启动新的部署任务",
                        buildDeployUrl(latest.getDeployKey()), latest.getDeployError());
            }
            // 先落 queued：真正的构建由队列 worker 按并发上限调度
            App deployClaim = new App();
            deployClaim.setId(appId);
            deployClaim.setDeployStatus(DeployStatusEnum.QUEUED.getValue());
            deployClaim.setDeployError("");
            deployClaim.setDeployOperatorId(loginUser.getId());
            boolean claimed = this.updateById(deployClaim);
            if (!claimed) {
                throw new BusinessException(ErrorCode.SYSTEM_ERROR, "提交部署任务失败，请重试");
            }
            // 入队失败（队列满）时要把状态放回 failed，避免应用卡在"排队中"
            try {
                sequence = deployQueueManager.submit(appId, this::handleDeployTask);
            } catch (RuntimeException e) {
                App rollback = new App();
                rollback.setId(appId);
                rollback.setDeployStatus(DeployStatusEnum.FAILED.getValue());
                rollback.setDeployError(resolveDeployErrorMessage(e));
                this.updateById(rollback);
                throw e;
            }
            log.info("已受理异步部署任务，appId: {}, 发起人: {}, 排队序号: {}, 队列长度: {}",
                    appId, loginUser.getId(), sequence, deployQueueManager.queueSize());
        } finally {
            submitLock.unlock();
        }

        Integer queuePosition = deployQueueManager.queuePosition(appId);
        String message = queuePosition == null
                ? "部署任务已提交，正在构建中，请稍后轮询部署状态"
                : "部署任务已进入队列（当前第 " + queuePosition + " 位），等待构建中";
        return DeployStatusVO.accepted(appId, DeployStatusEnum.QUEUED, message, queuePosition);
    }

    /**
     * 队列 worker 回调：真正执行构建与发布
     * <p>
     * 状态流转：queued -> deploying（开始构建）-> ready / failed。
     *
     * @param task 队列任务
     */
    public void handleDeployTask(DeployQueueManager.DeployTask task) {
        Long appId = task.appId();
        String threadName = Thread.currentThread().getName();
        try {
            App latest = this.getById(appId);
            if (latest == null) {
                log.warn("部署任务对应的应用已不存在，appId: {}, sequence: {}", appId, task.sequence());
                return;
            }
            // 进入构建阶段：先落 deploying，前端"排队中"会切到"部署中"
            App building = new App();
            building.setId(appId);
            building.setDeployStatus(DeployStatusEnum.DEPLOYING.getValue());
            building.setDeployError("");
            this.updateById(building);
            log.info("部署任务开始构建，appId: {}, sequence: {}, thread: {}", appId, task.sequence(), threadName);

            String sourceDirPath = buildSourceDirPath(latest.getCodeGenType(), appId);
            File sourceDir = new File(sourceDirPath);

            ReentrantLock deployLock = DEPLOY_LOCKS.computeIfAbsent(appId, key -> new ReentrantLock());
            deployLock.lock();
            String deployUrl;
            try {
                deployUrl = doDeploy(latest, appId, latest.getCodeGenType(), sourceDir);
            } finally {
                deployLock.unlock();
            }

            App ready = new App();
            // updateById 依赖实体主键，worker 线程里必须显式带上 appId
            ready.setId(appId);
            ready.setDeployStatus(DeployStatusEnum.READY.getValue());
            ready.setDeployError("");
            this.updateById(ready);
            log.info("部署任务完成，appId: {}, sequence: {}, 部署地址: {}, thread: {}", appId, task.sequence(), deployUrl, threadName);
        } catch (Throwable e) {
            String errorMessage = resolveDeployErrorMessage(e);
            log.error("部署任务失败，appId: {}, sequence: {}, thread: {}, error: {}", appId, task.sequence(), threadName, errorMessage, e);
            try {
                App failed = new App();
                failed.setId(appId);
                failed.setDeployStatus(DeployStatusEnum.FAILED.getValue());
                failed.setDeployError(errorMessage);
                this.updateById(failed);
            } catch (Exception updateError) {
                log.error("写入部署失败状态时出错，appId: {}", appId, updateError);
            }
        }
    }

    /**
     * 用中文描述应用当前部署状态（用于"未受理"提示）
     *
     * @param app 应用实体
     *
     * @return 状态描述
     */
    private String describeDeployStatus(App app) {
        DeployStatusEnum status = DeployStatusEnum.getEnumByValue(app.getDeployStatus());
        if (status == null) {
            return StrUtil.isNotBlank(app.getDeployKey()) ? "已部署" : "未部署";
        }
        return status.getText();
    }

    /**
     * 判断应用当前是否可以受理新的部署任务
     * <p>
     * 可受理：从未部署（状态为空）、空闲、上次失败，或"排队中/部署中"但已经超过 {@link #DEPLOY_STALE_MINUTES}
     * 没有动静（进程重启/任务异常退出留下的僵死状态）。
     *
     * @param app 应用实体
     *
     * @return 是否可以提交部署
     */
    private boolean isDeploySubmittable(App app) {
        if (app == null) {
            return false;
        }
        DeployStatusEnum status = DeployStatusEnum.getEnumByValue(app.getDeployStatus());
        if (status == null) {
            // 历史数据没有状态字段：没有 deployKey 视为可提交
            return StrUtil.isBlank(app.getDeployKey());
        }
        return switch (status) {
            case IDLE, FAILED -> true;
            // 已部署：只有在"代码被改过"之后才允许再次提交，这样用户改完代码能重新部署出新站点
            case READY -> isDeployedContentStale(app);
            case QUEUED, DEPLOYING -> app.getUpdateTime() != null
                    && app.getUpdateTime().isBefore(LocalDateTime.now().minusMinutes(DEPLOY_STALE_MINUTES));
        };
    }

    /**
     * 已部署的产物是否落后于当前代码（即"代码改过、需要重新部署"）
     * <p>
     * 判定依据是应用的编辑时间晚于最近一次部署完成时间：
     * <ul>
     *     <li>用户在对话页改了自己的应用 → {@code editTime} 被刷新 → 需要重新部署；</li>
     *     <li>部署本身不写 {@code editTime}，因此刚部署完不会被判定为"脏"；</li>
     *     <li>历史数据没有 {@code editTime}/{@code deployedTime} 时保守判为"不需要"，
     *     避免打开页面就显示"可重新部署"。</li>
     * </ul>
     *
     * @param app 应用实体
     *
     * @return true 表示需要重新部署
     */
    private boolean isDeployedContentStale(App app) {
        if (app == null || app.getEditTime() == null || app.getDeployedTime() == null) {
            return false;
        }
        return app.getEditTime().isAfter(app.getDeployedTime());
    }

    /**
     * 根据应用当前数据推导部署状态视图
     *
     * @param app 应用实体
     *
     * @return 部署状态视图
     */
    private DeployStatusVO buildDeployStatus(App app) {
        DeployStatusEnum status = DeployStatusEnum.getEnumByValue(app.getDeployStatus());
        // 历史数据没有状态字段：只要有 deployKey 就视为部署完成
        if (status == null) {
            if (StrUtil.isNotBlank(app.getDeployKey())) {
                DeployStatusVO vo = DeployStatusVO.ready(app.getId(), buildDeployUrl(app.getDeployKey()), app.getDeployedTime());
                fillDeployStale(vo, app);
                return vo;
            }
            return DeployStatusVO.of(app.getId(), DeployStatusEnum.IDLE, "尚未部署，可提交部署任务", null);
        }
        return switch (status) {
            case READY -> {
                DeployStatusVO vo = DeployStatusVO.ready(app.getId(), buildDeployUrl(app.getDeployKey()), app.getDeployedTime());
                if (fillDeployStale(vo, app)) {
                    // 代码改过：明确告诉用户"可以重新部署"，避免他一直看旧站点还以为部署坏了
                    vo.setMessage("代码已更新，可重新部署（当前线上仍是上一次部署的内容）");
                }
                yield vo;
            }
            case QUEUED -> DeployStatusVO.queued(app.getId(), deployQueueManager.queuePosition(app.getId()),
                    deployQueueManager.queueSize(), deployQueueManager.getWorkerCount());
            case DEPLOYING -> DeployStatusVO.of(app.getId(), DeployStatusEnum.DEPLOYING, "部署中，请稍后轮询部署状态", null);
            case FAILED -> DeployStatusVO.of(app.getId(), DeployStatusEnum.FAILED, "部署失败，可修改代码后重新提交部署", app.getDeployError());
            case IDLE -> DeployStatusVO.of(app.getId(), DeployStatusEnum.IDLE, "尚未部署，可提交部署任务", null);
        };
    }

    /**
     * 把"是否需要重新部署"写进状态视图
     *
     * @param vo  状态视图
     * @param app 应用实体
     *
     * @return 是否需要重新部署
     */
    private boolean fillDeployStale(DeployStatusVO vo, App app) {
        boolean stale = isDeployedContentStale(app);
        vo.setDeployStale(stale);
        return stale;
    }

    /**
     * 判断当前用户是否有权操作该应用的部署
     * <p>
     * 除了应用创建者，异步部署的发起人也允许查看状态：任务已经挂到后台，
     * 如果只认创建者，管理员/协作者发起部署后就无法跟踪进度。
     *
     * @param app       应用实体
     * @param loginUser 当前登录用户
     *
     * @return 是否有权限
     */
    private boolean isDeployOperator(App app, User loginUser) {
        if (app == null || loginUser == null) {
            return false;
        }
        Long loginUserId = loginUser.getId();
        return loginUserId.equals(app.getUserId()) || loginUserId.equals(app.getDeployOperatorId());
    }

    /**
     * 构造部署地址
     *
     * @param deployKey 部署标识
     *
     * @return 部署地址，deployKey 为空时返回 null
     */
    private String buildDeployUrl(String deployKey) {
        return StrUtil.isBlank(deployKey) ? null : String.format("%s/%s/", AppConstant.CODE_DEPLOY_HOST, deployKey);
    }

    /**
     * 构造源码目录路径
     *
     * @param codeGenType 代码生成类型
     * @param appId       应用ID
     *
     * @return 源码目录绝对路径
     */
    private String buildSourceDirPath(String codeGenType, Long appId) {
        return AppConstant.CODE_OUTPUT_ROOT_DIR + File.separator + codeGenType + "_" + appId;
    }

    /**
     * 把部署异常转换成面向用户的失败原因（不含堆栈，长度受限）
     *
     * @param e 异常
     *
     * @return 失败原因
     */
    private String resolveDeployErrorMessage(Throwable e) {
        String message = e == null ? null : e.getMessage();
        if (StrUtil.isBlank(message)) {
            message = e == null ? "部署失败" : e.getClass().getSimpleName();
        }
        return StrUtil.maxLength(message, DEPLOY_ERROR_MAX_LENGTH);
    }

    /**
     * 部署主体：CAS 抢占 deployKey -> 构建 -> 复制到部署目录 -> 提交部署信息
     *
     * @param app          应用实体（调用前的快照）
     * @param appId        应用ID
     * @param codeGenType  代码生成类型
     * @param sourceDir    源码目录
     *
     * @return 可访问的部署地址
     */
    private String doDeploy(App app, Long appId, String codeGenType, File sourceDir) {
        // 1. 抢占 deployKey：已有则复用；为空则 CAS 写入，抢不到就复用别人写入的值
        String deployKey = claimDeployKey(appId, app.getDeployKey());
        String deployDirPath = AppConstant.CODE_DEPLOY_ROOT_DIR + File.separator + deployKey;

        // 2. Vue 工程：在暂存目录构建，成功后再原子切换，最后发布 dist
        File publishDir = sourceDir;
        Path stagingDir = null;
        try {
            if (CodeGenTypeEnum.VUE_PROJECT == CodeGenTypeEnum.getEnumByValue(codeGenType)) {
                stagingDir = createBuildStagingDir(sourceDir, appId);
                boolean buildSuccess = vueProjectBuilder.buildProjectTo(stagingDir.toString());
                if (!buildSuccess) {
                    // 构建失败：回滚 deployKey，保证下一次重试能重新抢占同一条部署路径
                    releaseDeployKeyOnFailure(appId, deployKey);
                    throw new BusinessException(ErrorCode.SYSTEM_ERROR, "Vue 项目构建失败，请重试");
                }
                File stagedDist = stagingDir.resolve("dist").toFile();
                if (!stagedDist.isDirectory()) {
                    releaseDeployKeyOnFailure(appId, deployKey);
                    throw new BusinessException(ErrorCode.SYSTEM_ERROR, "Vue 项目构建完成但 dist 目录未生成");
                }
                // 原子切换：先把暂存产物移到源码目录（同盘 rename，不产生中间态目录）
                try {
                    replaceDirectory(stagedDist.toPath(), sourceDir.toPath().resolve("dist"));
                } catch (IOException e) {
                    releaseDeployKeyOnFailure(appId, deployKey);
                    log.error("构建产物原子切换失败，appId: {}", appId, e);
                    throw new BusinessException(ErrorCode.SYSTEM_ERROR, "构建产物切换失败：" + e.getMessage());
                }
                publishDir = new File(sourceDir, "dist");
            }

            // 3. 复制最终产物到部署目录
            try {
                FileUtil.copyContent(publishDir, new File(deployDirPath), true);
            } catch (Exception e) {
                releaseDeployKeyOnFailure(appId, deployKey);
                log.error("部署应用失败，appId: {}", appId, e);
                throw new BusinessException(ErrorCode.SYSTEM_ERROR, "部署应用失败：" + e.getMessage());
            }

            // 4. 提交部署信息（deployKey 已在抢占阶段落库，这里只更新时间与状态）
            //    同步路径同样落 ready 状态，保证"状态查询接口"在任何部署方式下都反映真实情况
            App updateApp = new App();
            updateApp.setId(appId);
            updateApp.setDeployKey(deployKey);
            updateApp.setDeployedTime(LocalDateTime.now());
            updateApp.setDeployStatus(DeployStatusEnum.READY.getValue());
            updateApp.setDeployError("");
            boolean updateResult = this.updateById(updateApp);
            if (!updateResult) {
                releaseDeployKeyOnFailure(appId, deployKey);
                throw new BusinessException(ErrorCode.SYSTEM_ERROR, "更新应用部署信息失败");
            }

            // 5. 返回部署地址并异步生成封面截图
            String appDeployUrl = String.format("%s/%s/", AppConstant.CODE_DEPLOY_HOST, deployKey);
            log.info("应用部署成功，appId: {}, 部署地址: {}, 部署目录: {}", appId, appDeployUrl, deployDirPath);
            generateAndUploadScreenshotAsync(appId, appDeployUrl);
            return appDeployUrl;
        } finally {
            if (stagingDir != null) {
                FileUtil.del(stagingDir.toFile());
            }
        }
    }

    /**
     * 抢占 deployKey（CAS + 重试 + 幂等）
     * <p>
     * 关键点：用条件更新 {@code WHERE id=? AND deploy_key IS NULL} 保证只有一个并发请求写入成功，
     * 其它请求（包括"别人正在部署同一个应用"）直接复用数据库里已有的 key。
     *
     * @param appId            应用ID
     * @param currentDeployKey 调用前读到的 deployKey（可能为空）
     *
     * @return 最终生效的 deployKey（一定与数据库当前值一致）
     */
    private String claimDeployKey(Long appId, String currentDeployKey) {
        if (StrUtil.isNotBlank(currentDeployKey)) {
            return currentDeployKey;
        }
        for (int attempt = 0; attempt < DEPLOY_KEY_MAX_ATTEMPTS; attempt++) {
            String candidate = RandomUtil.randomString(6);
            try {
                // 条件更新（CAS）：只有 deploy_key 仍为 NULL 时才会命中，且只影响一行
                App claim = new App();
                claim.setDeployKey(candidate);
                claim.setDeployedTime(LocalDateTime.now());
                QueryCondition casCondition = QueryCondition.create(new QueryColumn("id"), appId)
                        .and(new QueryColumn("deploy_key").isNull(true));
                int affected = this.getMapper().updateByCondition(claim, casCondition);
                if (affected == 1) {
                    log.info("应用抢占 deployKey 成功，appId: {}, deployKey: {}", appId, candidate);
                    return candidate;
                }
            } catch (DuplicateKeyException e) {
                // 6 位随机串与历史 key 撞车：换一个再试
                log.warn("deployKey 冲突，重试。appId: {}, candidate: {}", appId, candidate);
                continue;
            }
            // 条件更新未命中：说明并发的其它请求已经写入，直接复用
            App latest = this.getById(appId);
            String existing = latest == null ? null : latest.getDeployKey();
            if (StrUtil.isNotBlank(existing)) {
                log.info("应用已由并发请求完成 deployKey 抢占，复用：appId: {}, deployKey: {}", appId, existing);
                return existing;
            }
        }
        // 极端情况：条件更新一直不命中且查不到值（例如应用刚被删除）
        App latest = this.getById(appId);
        String existing = latest == null ? null : latest.getDeployKey();
        if (StrUtil.isNotBlank(existing)) {
            return existing;
        }
        throw new BusinessException(ErrorCode.SYSTEM_ERROR, "分配部署标识失败，请重试");
    }

    /**
     * 部署失败时释放 deployKey（回滚为 NULL），让下一次部署可以复用同一条路径重试
     *
     * @param appId     应用ID
     * @param deployKey 当前占用的 deployKey
     */
    private void releaseDeployKeyOnFailure(Long appId, String deployKey) {
        if (StrUtil.isBlank(deployKey)) {
            return;
        }
        try {
            // 用 UpdateWrapper 显式把列置为 SQL NULL（普通 set(null) 会被当作"不更新该字段"）
            App release = com.mybatisflex.core.update.UpdateWrapper
                    .of(new App())
                    .setRaw("deploy_key", "NULL")
                    .toEntity();
            QueryCondition condition = QueryCondition.create(new QueryColumn("id"), appId)
                    .and(QueryCondition.create(new QueryColumn("deploy_key"), deployKey));            int affected = this.getMapper().updateByCondition(release, condition);
            log.warn("部署失败，已释放 deployKey，appId: {}, deployKey: {}, affected: {}", appId, deployKey, affected);
        } catch (Exception e) {
            log.error("部署失败后释放 deployKey 出错，appId: {}, deployKey: {}", appId, deployKey, e);
        }
    }

    /**
     * 创建构建暂存目录（与源码目录同级，保证后续 rename 在同一个分区内，才能真正原子）
     *
     * @param sourceDir 源码目录
     * @param appId     应用ID
     *
     * @return 暂存目录路径
     */
    private Path createBuildStagingDir(File sourceDir, Long appId) {
        Path staging = sourceDir.toPath().resolveSibling(BUILD_STAGING_PREFIX + appId + "-" + System.nanoTime());
        FileUtil.mkdir(staging.toFile());
        try {
            // 只复制构建需要的内容，避免把上一次的 dist/node_modules 带进暂存目录
            FileUtil.copyContent(sourceDir, staging.toFile(), true);
            FileUtil.del(staging.resolve("dist").toFile());
        } catch (Exception e) {
            FileUtil.del(staging.toFile());
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "准备构建暂存目录失败：" + e.getMessage());
        }
        return staging;
    }

    /**
     * 用新目录替换目标目录（先备份旧目录，失败时回滚）
     * <p>
     * 目标：Windows 下 {@code Files.move} 无法覆盖已存在目录，因此采用
     * "旧目录改名备份 -> 新目录改名就位 -> 删除备份" 的顺序，任何一步失败都恢复旧目录。
     *
     * @param newDir    新的目录（构建产物）
     * @param targetDir 目标目录（如源代码目录下的 dist）
     *
     * @throws IOException 切换失败（旧目录已恢复）
     */
    private void replaceDirectory(Path newDir, Path targetDir) throws IOException {
        Files.createDirectories(targetDir.getParent());
        Path backup = null;
        if (Files.exists(targetDir)) {
            backup = targetDir.resolveSibling(targetDir.getFileName() + ".bak-" + System.nanoTime());
            moveDirectory(targetDir, backup);
        }
        try {
            moveDirectory(newDir, targetDir);
        } catch (IOException e) {
            if (backup != null) {
                moveDirectory(backup, targetDir);
            }
            throw e;
        }
        if (backup != null) {
            FileUtil.del(backup.toFile());
        }
    }

    /**
     * 目录移动：优先用原子 rename，跨分区场景退化为复制 + 删除
     *
     * @param from 源目录
     * @param to   目标目录
     *
     * @throws IOException 移动失败
     */
    private void moveDirectory(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
        } catch (FileAlreadyExistsException e) {
            throw e;
        } catch (IOException e) {
            // 跨分区或文件系统不支持原子移动时退化为复制
            FileUtil.copyContent(from.toFile(), to.toFile(), true);
            FileUtil.del(from.toFile());
        }
    }


    /**
     * 异步生成截图并上传到COS，更新应用封面
     *
     * @param appId        应用ID
     * @param appDeployUrl 部署地址
     */
    public void generateAndUploadScreenshotAsync(Long appId, String appDeployUrl) {
        // 异步执行截图生成和上传
        Thread.startVirtualThread(() -> {
            try {
                String screenshotUrl = screenshotService.generateAndUploadScreenshot(appDeployUrl);
                App app = new App();
                app.setId(appId);
                app.setCover(screenshotUrl);
                boolean updateResult = this.updateById(app);
                ThrowUtils.throwIf(!updateResult, ErrorCode.SYSTEM_ERROR, "更新应用封面失败");
                log.info("异步生成截图并上传到COS成功，appId: {}, app: {}", appId, app);
            } catch (Exception e) {
                log.error("异步生成截图并上传到COS失败，appId: {}, 部署地址: {}, error: {}", appId, appDeployUrl, ExceptionUtil.stacktraceToString(e));
            }
        });
    }

    /**
     * 删除应用，并关联删除该应用的所有对话历史
     *
     * @param appId 应用id
     *
     * @return 是否删除成功
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean deleteApp(Long appId) {
        // 1. 校验参数
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR, "应用ID不合法");
        // 2. 删除应用（逻辑删除）
        boolean appResult = this.removeById(appId);
        ThrowUtils.throwIf(!appResult, ErrorCode.SYSTEM_ERROR, "删除应用失败");
        // 3. 关联删除该应用的所有对话历史，避免数据冗余
        boolean chatHistoryResult = chatHistoryService.deleteByAppId(appId);
        ThrowUtils.throwIf(!chatHistoryResult, ErrorCode.SYSTEM_ERROR, "删除应用的对话历史失败");
        return true;
    }
}
