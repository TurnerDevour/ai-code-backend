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
import com.example.aicodebackend.common.PageRequest;
import com.example.aicodebackend.constant.AppConstant;
import com.example.aicodebackend.constant.ChatHistoryConstant;
import com.example.aicodebackend.core.AiCodeGeneratorFacade;
import com.example.aicodebackend.core.builder.DeployHeartbeat;
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
import com.example.aicodebackend.utils.AppPathUtils;
import com.example.aicodebackend.utils.ThrowableUtils;
import com.mybatisflex.core.query.QueryColumn;
import com.mybatisflex.core.query.QueryCondition;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.core.update.UpdateWrapper;
import com.mybatisflex.spring.service.impl.ServiceImpl;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

@Slf4j
@Service
public class AppServiceImpl extends ServiceImpl<AppMapper, App> implements AppService {

    @Value("${code.deploy-host:http://localhost}")
    private String deployHost;

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
    private DeployHeartbeat deployHeartbeat;

    @Resource
    private GenerationTaskRegistry generationTaskRegistry;

    /**
     * 应用 id -> 对话生成信号量（容量 1）
     * 同一个应用的并发对话请求会共用同一个 {@code MessageWindowChatMemory} 实例（见 AiCodeGeneratorServiceFactory），
     * 而该记忆实例与其 Redis 存储都没有并发保护：并发写会互相覆盖，导致对话记忆丢更新……
     */
    private static final Map<Long, SemaphoreRef> CHAT_SEMAPHORES = new ConcurrentHashMap<>();

    /**
     * 应用 id -> 部署执行锁：保证同一个应用的"构建 + 复制到部署目录"串行执行，
     * 避免两个部署同时写同一个 dist / 同一个部署目录。
     * 与对话信号量一样带引用计数，最后一个使用者退出后移除，避免静态 Map 无限增长。
     */
    private static final Map<Long, LockRef> DEPLOY_LOCKS = new ConcurrentHashMap<>();

    /**
     * 应用 id -> 部署提交检查锁：异步部署提交时在锁内完成"读状态 -> 落 queued"，
     * 减少本实例内的无谓竞争（跨实例由数据库状态 CAS 保证）。
     * 必须与 {@link #DEPLOY_LOCKS} 分开：提交检查要快速返回（否则并发提交会一直等到构建结束）……
     */
    private static final Map<Long, LockRef> DEPLOY_SUBMIT_CHECKS = new ConcurrentHashMap<>();

    /**
     * 带引用计数的锁：users 归零时从 Map 移除，避免 "应用 id -> 锁" 的静态 Map 无限增长。
     * 关键约束：先从 Map 取到引用（users+1），再 lock/unlock，最后 release 引用；
     * 只有 users=0 才会移除，所以不会出现"A 还持有旧锁、B 拿到新锁"的并发漏洞。
     */
    static final class LockRef {

        final ReentrantLock lock = new ReentrantLock();

        final AtomicInteger users = new AtomicInteger();
    }

    /** 带引用计数的信号量（对话生成串行化用） */
    private static final class SemaphoreRef {

        final Semaphore semaphore = new Semaphore(1);

        final AtomicInteger users = new AtomicInteger();
    }

    /** 获取部署执行锁引用（必须与 {@link #releaseDeployLock} 配对） */
    static LockRef acquireDeployLock(Long appId) {
        return acquireLockRef(DEPLOY_LOCKS, appId);
    }

    /** 释放部署执行锁引用 */
    static void releaseDeployLock(Long appId, LockRef ref) {
        releaseLockRef(DEPLOY_LOCKS, appId, ref);
    }

    /** 获取部署提交检查锁引用（必须与 {@link #releaseSubmitLock} 配对） */
    private static LockRef acquireSubmitLock(Long appId) {
        return acquireLockRef(DEPLOY_SUBMIT_CHECKS, appId);
    }

    /** 释放部署提交检查锁引用 */
    private static void releaseSubmitLock(Long appId, LockRef ref) {
        releaseLockRef(DEPLOY_SUBMIT_CHECKS, appId, ref);
    }

    /** 通用锁引用获取 */
    private static LockRef acquireLockRef(Map<Long, LockRef> refs, Long appId) {
        return refs.compute(appId, (key, existing) -> {
            LockRef ref = existing == null ? new LockRef() : existing;
            ref.users.incrementAndGet();
            return ref;
        });
    }

    /** 通用锁引用释放：最后一个使用者退出时移除 Map 条目 */
    private static void releaseLockRef(Map<Long, LockRef> refs, Long appId, LockRef ref) {
        refs.computeIfPresent(appId, (key, existing) -> {
            if (existing != ref) {
                // 理论上不会发生：compose 的 acquire/release 总是成对且持有同一引用
                return existing;
            }
            return ref.users.updateAndGet(current -> Math.max(0, current - 1)) == 0 ? null : ref;
        });
    }

    /** 获取对话生成信号量引用（必须与 {@link #releaseChatSemaphore} 配对） */
    private static SemaphoreRef acquireChatSemaphore(Long appId) {
        return CHAT_SEMAPHORES.compute(appId, (key, existing) -> {
            SemaphoreRef ref = existing == null ? new SemaphoreRef() : existing;
            ref.users.incrementAndGet();
            return ref;
        });
    }

    /** 释放对话生成信号量引用（在 semaphore.release() 之后调用） */
    private static void releaseChatSemaphore(Long appId, SemaphoreRef ref) {
        CHAT_SEMAPHORES.computeIfPresent(appId, (key, existing) -> {
            if (existing != ref) {
                return existing;
            }
            return ref.users.updateAndGet(current -> Math.max(0, current - 1)) == 0 ? null : ref;
        });
    }

    /** 对话生成锁的最长等待时间（秒） */
    private static final long CHAT_LOCK_WAIT_SECONDS = 300;

    /** 部署 deployKey 落库的最大重试次数（6 位随机串空间较小，理论上可能与历史 key 撞车） */
    private static final int DEPLOY_KEY_MAX_ATTEMPTS = 5;

    /**
     * "部署中"状态被视为僵死（可重新提交）的分钟数
     * 覆盖两类情况：进程在部署过程中重启、构建任务异常退出没来得及回写状态。
     * 正常构建（含 npm install）远小于该阈值。
     */
    private static final long DEPLOY_STALE_MINUTES = 30;

    /**
     * 同步部署等待"同一应用部署锁"的最长时间（毫秒）
     * 一次构建要 1~3 分钟，等锁超过 1 秒基本不可能等到本轮构建结束，继续等只会白占 Web 线程。
     * 这 1 秒只用来吸收双击/重试造成的瞬时重叠；等不到就让调用方改用异步接口。
     */
    private static final long SYNC_DEPLOY_LOCK_WAIT_MILLIS = 1_000;

    /** 落库的部署失败原因最大长度 */
    private static final int DEPLOY_ERROR_MAX_LENGTH = 500;

    /** Vue 工程构建的暂存目录前缀：构建发生在暂存目录里，成功后再原子切换进源码目录 */
    private static final String BUILD_STAGING_PREFIX = ".build-staging-";

    /**
     * 复制到暂存目录时要跳过的工程根目录：这两个目录都由本次构建自己生成，带了反而会出错
     * 特别是 {@code node_modules}：Linux 上 npm 会把 {@code node_modules/.bin/*} 做成符号链接，
     * 而 Hutool 的目录复制底层是 {@code Files.copy}（默认跟随符号链接），会把链接"实体化"成真实文件——……
     */
    private static final Set<String> STAGING_SKIP_DIRS = Set.of("node_modules", "dist");

    /** 获取脱敏后的应用信息 */
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

    /** 获取脱敏后的应用信息列表 */
    @Override
    public List<AppVO> getAppVOList(List<App> appList) {

        if (CollUtil.isEmpty(appList)) {
            return Collections.emptyList();
        }

        Set<Long> userIds = appList.stream()
                .map(App::getUserId)
                .collect(Collectors.toSet());
        Map<Long, UserVO> userVOMap = userService.listByIds(userIds).stream()
                .collect(Collectors.toMap(User::getId, userService::getUserVO));

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

    /** 获取查询条件包装器 */
    @Override
    public QueryWrapper getQueryWrapper(AppQueryRequest appQueryRequest) {

        if (appQueryRequest == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "参数为空");
        }

        Long id = appQueryRequest.getId();
        String appName = appQueryRequest.getAppName();
        String cover = appQueryRequest.getCover();
        String initPrompt = appQueryRequest.getInitPrompt();
        String codeGenType = appQueryRequest.getCodeGenType();
        String deployKey = appQueryRequest.getDeployKey();
        Integer priority = appQueryRequest.getPriority();
        Long userId = appQueryRequest.getUserId();
        String sortField = appQueryRequest.getSortField();
        QueryWrapper queryWrapper = QueryWrapper.create()
                .eq("id", id)
                .eq("priority", priority)
                .eq("user_id", userId)
                .like("app_name", appName)
                .like("cover", cover)
                .like("init_prompt", initPrompt)
                .like("code_gen_type", codeGenType)
                .like("deploy_key", deployKey);

        if (StrUtil.isNotBlank(sortField)) {
            queryWrapper.orderBy(sortField, appQueryRequest.isAscending());
        } else {
            queryWrapper.orderBy(PageRequest.DEFAULT_SORT_FIELD, false);
        }
        return queryWrapper;
    }

    /** 根据应用ID和提示生成代码，并以流式方式返回生成的代码。 */
    @Override
    public Flux<GenerationTaskRegistry.SequencedFrame> chatToGenCode(Long appId, String prompt, User loginUser) {

        ThrowUtils.throwIf(appId == null, ErrorCode.PARAMS_ERROR, "应用ID为空");
        ThrowUtils.throwIf(StrUtil.isBlank(prompt), ErrorCode.PARAMS_ERROR, "提示为空");

        App app = requireOwnedApp(appId, loginUser);

        String codeGenType = app.getCodeGenType();
        CodeGenTypeEnum codeGenTypeEnum = CodeGenTypeEnum.getEnumByValue(codeGenType);
        if (codeGenTypeEnum == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "不支持的代码生成类型");
        }

        AIModelTypeEnum aiModelTypeEnum = AIModelTypeEnum.getEnumByValue(app.getAiModelType());

        //    应用级对话记忆是共享的、无并发保护，同时进入会让记忆互相覆盖（丢上下文），
        //    因此这里等待（而不是立刻失败），等待超时后再给出明确提示，而不是静默损坏记忆。
        SemaphoreRef chatRef = acquireChatSemaphore(appId);
        Semaphore chatSemaphore = chatRef.semaphore;
        boolean locked;
        try {
            locked = chatSemaphore.tryAcquire(CHAT_LOCK_WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            releaseChatSemaphore(appId, chatRef);
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "生成请求等待被中断，请重试");
        }
        if (!locked) {
            releaseChatSemaphore(appId, chatRef);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "该应用正在生成中，请等待上一轮生成结束");
        }
        // 信号量可能在响应式流的另一个线程释放；引用计数必须和 release 一起做
        Runnable releaseChat = () -> {
            chatSemaphore.release();
            releaseChatSemaphore(appId, chatRef);
        };
        try {

            chatHistoryService.addChatMessage(appId, loginUser.getId(), prompt, ChatMessageTypeEnum.USER);

            //     VUE_PROJECT（多轮工具调用、耗时最长）走"生成任务注册表"——生成在服务端独立运行，
            //     客户端断开只解绑自己的订阅，历史里拿到的是完整内容（方案 C）；
            //     其它类型沿用原来的流处理（本次未改）。
            if (codeGenTypeEnum == CodeGenTypeEnum.VUE_PROJECT) {
                Flux<GenerationTaskRegistry.SequencedFrame> registryStream =
                        chatToGenCodeWithRegistry(appId, prompt, loginUser, codeGenTypeEnum, aiModelTypeEnum);
                return registryStream.doFinally(signalType -> releaseChat.run());
            }

            Flux<String> codeStream = aiCodeGeneratorFacade.generateAndSaveCodeStream(prompt, codeGenTypeEnum, appId, aiModelTypeEnum);

            Flux<String> handledStream = streamHandlerExecutor.doExecute(codeStream, chatHistoryService, appId, loginUser, codeGenTypeEnum);

            //    这里只做"释放 + 兜底下发一个结束空帧"，不改变正常消息类型（HTML/MULTI_FILE 仍是纯文本增量）。
            //    HTML / MULTI_FILE 没有"续订"概念，序号按到达顺序递增（客户端不使用它）。
            AtomicLong htmlSeq = new AtomicLong();
            return handledStream
                    .doFinally(signalType -> releaseChat.run())
                    .concatWith(Flux.just(""))
                    .onErrorResume(error -> {
                        if (isClientDisconnected(error)) {
                            log.warn("客户端已断开，生成流提前结束，appId: {}", appId);
                            return Flux.empty();
                        }
                        log.error("生成代码流式响应失败，appId: {}", appId, error);
                        // 业务异常是"我们已经判断清楚并给出处置建议"的失败（例如模型思考跑飞被主动中止），
                        // 它的原因必须原样告诉用户；未知异常才退化成通用文案，避免泄露内部细节
                        String reason = userFacingReason(error);
                        return Flux.just(JSONUtil.toJsonStr(new ErrorMessage(reason)));
                    })
                    .map(chunk -> new GenerationTaskRegistry.SequencedFrame(htmlSeq.incrementAndGet(), chunk));
        } catch (RuntimeException | Error e) {
            // 同步阶段就失败（例如保存消息失败）时，doFinally 还没注册，必须在这里释放信号量与引用
            releaseChat.run();
            throw e;
        }
    }

    /**
     * VUE_PROJECT：走"生成任务注册表"的新链路（方案 C）
     * 生成在服务端独立运行（累积 + 结束后落库完整内容），返回给客户端的只是"订阅这份输出"；
     * 客户端断开只解绑自己的订阅，不会取消生成，也不会让历史里只有半截内容。
     */
    private Flux<GenerationTaskRegistry.SequencedFrame> chatToGenCodeWithRegistry(Long appId,
                                                                                  String prompt,
                                                                                  User loginUser,
                                                                                  CodeGenTypeEnum codeGenTypeEnum,
                                                                                  AIModelTypeEnum aiModelTypeEnum) {
        String subId = "sub-" + appId + "-" + System.nanoTime();
        GenerationTaskRegistry.GenerationTask task = generationTaskRegistry.submitOrGet(appId, loginUser,
                () -> buildRegistryUpstream(appId, prompt, codeGenTypeEnum, aiModelTypeEnum));
        return generationTaskRegistry.subscribe(task, subId, 0L);
    }

    /**
     * 构建注册表用的上游流：把生成结果拆成"下发帧"与"累积文本"
     * 下发帧与原来的 SSE 格式保持一致（复用 StreamMessageTypeEnum 的 JSON 结构），
     * 累积文本用于落库：文本增量取原文，工具执行结果带上完整入参，保证「查看对话」能看到完整历史。
     */
    private Flux<GenerationTaskRegistry.GenerationEmit> buildRegistryUpstream(Long appId,
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
     * 把一条流式消息转换成"下发帧 + 正文累积 + 思考过程累积"
     * 思考过程（{@code type=ai_thinking}）走单独的累积通道：它要单独落库到
     * {@code chat_history.thinking} 并单独展示，绝不能并进正文……
     */
    private GenerationTaskRegistry.GenerationEmit toRegistryEmit(String messageJson) {
        if (StrUtil.isBlank(messageJson)) {
            return new GenerationTaskRegistry.GenerationEmit("", "");
        }
        JSONObject json;
        try {
            json = JSONUtil.parseObj(messageJson);
        } catch (Exception e) {
            log.warn("解析流式消息失败（不影响下发）：{}", e.getMessage());
            return new GenerationTaskRegistry.GenerationEmit(messageJson, "");
        }
        if (StreamMessageTypeEnum.AI_THINKING.getValue().equals(json.getStr("type"))) {
            return new GenerationTaskRegistry.GenerationEmit(messageJson, "",
                    StrUtil.nullToEmpty(json.getStr("data")));
        }
        return new GenerationTaskRegistry.GenerationEmit(messageJson, extractHistoryContent(json));
    }

    /** 从流式消息里取出需要累积进对话历史正文的内容 */
    private String extractHistoryContent(JSONObject json) {
        String type = json.getStr("type");
        if (StreamMessageTypeEnum.AI_RESPONSE.getValue().equals(type)) {
            return StrUtil.nullToEmpty(json.getStr("data"));
        }
        if (StreamMessageTypeEnum.TOOL_EXECUTED.getValue().equals(type)) {
            // 工具执行结果：记录"工具名 + 完整入参"，保证历史里能看到写了哪些文件与内容
            String name = StrUtil.nullToEmpty(json.getStr("name"));
            String arguments = StrUtil.nullToEmpty(json.getStr("arguments"));
            // 失败必须留下明确痕迹：LangChain4j 对"参数不合法 / 工具名不存在 / 工具内部异常"
            // 同样会回调 onToolExecuted，若不区分，历史里"写了文件"和"根本没写"完全一样，
            // 用户只会看到"AI 说改好了、页面没变"（实测问题）。
            if (json.getBool("failed", false)) {
                String reason = StrUtil.maxLength(StrUtil.nullToEmpty(json.getStr("result")),
                        ChatHistoryConstant.TOOL_FAILURE_REASON_MAX_LENGTH);
                return "\n⚠️ [工具调用失败] " + name + " " + arguments + "\n失败原因：" + reason + "\n";
            }
            return "\n[工具调用] " + name + " " + arguments + "\n";
        }
        return "";
    }

    /** 续订当前应用的生成流（方案 C） */
    @Override
    public Flux<GenerationTaskRegistry.SequencedFrame> resumeGenCode(Long appId, long fromSeq, String subId, User loginUser) {
        ThrowUtils.throwIfInvalidId(appId, "应用ID");
        ThrowUtils.throwIf(loginUser == null, ErrorCode.PARAMS_ERROR, "用户未登录");
        App app = requireOwnedApp(appId, loginUser);
        if (!isVueProject(app.getCodeGenType())) {
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

    /** 查询当前生成任务状态（方案 C） */
    @Override
    public GenerationStatusVO getGenStatus(Long appId, User loginUser) {
        ThrowUtils.throwIfInvalidId(appId, "应用ID");
        ThrowUtils.throwIf(loginUser == null, ErrorCode.PARAMS_ERROR, "用户未登录");
        App app = requireOwnedApp(appId, loginUser);
        return generationTaskRegistry.find(appId)
                .map(task -> {
                    GenerationStatusVO vo = new GenerationStatusVO();
                    vo.setAppId(appId);
                    vo.setStatus(task.getStatus().getValue());
                    vo.setRunning(task.isRunning());
                    vo.setContentLength(task.contentLength());
                    vo.setLastSeq(task.getFrameSeq());
                    vo.setErrorMessage(task.getErrorMessage());
                    // Vue 工程的 dist 是生成结束后异步构建的：把构建状态一并下发，
                    // 前端据此在构建完成后再刷新预览（否则会看到空白页）
                    vo.setBuildStatus(task.getBuildStatus().getValue());
                    vo.setBuildError(StrUtil.nullToEmpty(task.getBuildError()));
                    vo.setMessage(switch (task.getStatus()) {
                        case RUNNING -> "正在生成中（服务端独立运行，可随时断开或续订）";
                        case FINISHED -> "生成已完成，完整内容已写入对话历史";
                        case FAILED -> "生成失败，可重新发起";
                    });
                    return vo;
                })
                .orElseGet(() -> noTaskStatus(appId, app));
    }

    /**
     * 没有生成任务时的状态（Vue 工程会额外报告"产物是否已经就绪"）
     * 为什么不能一律回 {@code buildStatus=idle}：生成任务注册表是进程内的，后端一重启
     * 所有任务就都没了；此时如果仍然告诉前端"构建还没做完"，前端就会每 1.5 秒轮询一次状态接口……
     */
    private GenerationStatusVO noTaskStatus(Long appId, App app) {
        GenerationStatusVO vo = GenerationStatusVO.none(appId);
        if (!isVueProject(app.getCodeGenType())) {
            return vo;
        }
        File distIndex = AppPathUtils.distIndex(app.getCodeGenType(), appId).toFile();
        if (distIndex.isFile()) {
            vo.setBuildStatus(GenerationTaskRegistry.BuildStatus.FINISHED.getValue());
            vo.setMessage("当前没有进行中的生成任务，构建产物已就绪");
        }
        return vo;
    }

    /**
     * 判断异常是否由客户端主动断开引起（如用户关闭页面、网关超时）
     * 这类异常不是业务失败：SSE 响应已经提交，再回写任何内容都没有意义，
     * 继续按异常上报只会在日志里制造噪声，因此单独识别出来降级为 warn。
     */
    private boolean isClientDisconnected(Throwable error) {
        return ThrowableUtils.anyCauseMatches(error, cause -> {
            String name = cause.getClass().getName();
            return name.contains("ClientAbortException")
                    || name.contains("AsyncRequestNotUsableException")
                    || name.contains("EofException")
                    || cause instanceof IOException;
        });
    }

    /**
     * 取"可以原样展示给用户"的失败原因
     * 业务异常（{@link BusinessException}）是我们自己判定的失败，消息本身就是给用户看的处置建议
     * （例如"模型思考跑飞已中止，请拆小需求"）；其它异常一律退化成通用文案……
     */
    private String userFacingReason(Throwable error) {
        BusinessException businessException = ThrowableUtils.findCause(error, BusinessException.class);
        if (businessException != null && StrUtil.isNotBlank(businessException.getMessage())) {
            return businessException.getMessage();
        }
        return "代码生成失败，请稍后重试";
    }

    /**
     * 部署应用
     * 并发语义（本次修复的核心）：
     * 1. deployKey 的"生成 + 落库"是一个 CAS 过程：只有 deploy_key 为空时才能写入，其余并发请求复用已有值；
     */
    @Override
    public String deployApp(Long appId, User loginUser) {

        ThrowUtils.throwIf(appId == null, ErrorCode.PARAMS_ERROR, "应用ID为空");
        ThrowUtils.throwIf(loginUser == null, ErrorCode.PARAMS_ERROR, "用户未登录");

        App app = this.getById(appId);
        ThrowUtils.throwIf(app == null, ErrorCode.NOT_FOUND_ERROR, "应用不存在");
        ThrowUtils.throwIf(!app.getUserId().equals(loginUser.getId()), ErrorCode.NO_AUTH_ERROR, "无权限访问该应用");

        String codeGenType = app.getCodeGenType();
        File sourceDir = new File(buildSourceDirPath(codeGenType, appId));
        if (!sourceDir.exists() || !sourceDir.isDirectory()) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "源代码目录不存在，请先生成代码");
        }
        // 状态机预检：没必要为"本来就不会被受理"的请求去等锁/等构建额度。
        // 拿到额度后还会再读一次数据库复检，这里只负责快速失败。
        ensureDeploySubmittable(app);

        //    锁必须有界等待：构建要 1~3 分钟，如果无界 lock()，第二个同步请求会一直挂在 Web 线程上，
        //    完全绕过 runWithPermit 的"快失败"设计（同应用的异步构建也持有这把锁）。
        //    锁引用带计数，全部使用者退出后从静态 Map 移除，避免 Map 随应用数无限增长。
        LockRef lockRef = acquireDeployLock(appId);
        try {
            boolean locked;
            deployQueueManager.syncLockWaitStarted();
            try {
                locked = lockRef.lock.tryLock(SYNC_DEPLOY_LOCK_WAIT_MILLIS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new BusinessException(ErrorCode.TOO_MANY_REQUESTS, "部署等待被中断，请重试");
            } finally {
                deployQueueManager.syncLockWaitFinished();
            }
            if (!locked) {
                throw new BusinessException(ErrorCode.TOO_MANY_REQUESTS,
                        "该应用正在部署中，请勿重复提交；可改用异步部署接口 /app/deploy/async 轮询状态");
            }
            String taskId = UUID.randomUUID().toString();
            try {

                //    否则 100 个同步部署请求依然会拉起 100 个 npm 进程（实测部署成功率掉到 46%）。
                //    闸门内部是"快失败 + 有界短排队"（见 DeployQueueManager#runWithPermit）；
                //    "抢部署状态 + 构建"放在拿到额度之后做，避免排队期间把应用误标成 deploying。
                return deployQueueManager.runWithPermit(() -> doSyncDeploy(appId, loginUser.getId(), taskId));
            } catch (BusinessException e) {
                throw e;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new BusinessException(ErrorCode.TOO_MANY_REQUESTS, "部署等待被中断，请重试");
            } finally {
                lockRef.lock.unlock();
            }
        } finally {
            releaseDeployLock(appId, lockRef);
        }
    }

    /**
     * 同步部署的主体（已经拿到构建额度，且持有该应用的部署锁）
     * 同步接口以前不落 {@code deploy_status}，导致构建期间状态查询看不到"部署中"、
     * 异步接口也可能在同一时间被受理。现在它和异步任务走同一套状态机：
     */
    private String doSyncDeploy(Long appId, Long operatorId, String taskId) {
        App latest = this.getById(appId);
        if (latest == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "应用不存在");
        }
        ensureDeploySubmittable(latest);
        if (!claimSyncDeploying(appId, latest.getDeployStatus(), operatorId, taskId)) {
            throw new BusinessException(ErrorCode.TOO_MANY_REQUESTS,
                    "部署状态已变化（可能有其他部署任务正在进行），请稍后重试或改用异步部署接口");
        }
        String codeGenType = latest.getCodeGenType();
        File sourceDir = new File(buildSourceDirPath(codeGenType, appId));
        DeployHeartbeat.Handle heartbeat = deployHeartbeat.start(appId, taskId);
        try {
            return doDeploy(latest, appId, codeGenType, sourceDir);
        } catch (RuntimeException | Error e) {
            // doDeploy 内部已经按失败路径回滚 deployKey；这里补状态，避免应用永远停在 deploying
            try {
                markDeployFailed(appId, resolveDeployErrorMessage(e));
            } catch (RuntimeException markError) {
                e.addSuppressed(markError);
                log.error("同步部署失败后写入失败状态出错，appId: {}", appId, markError);
            }
            throw e;
        } finally {
            if (heartbeat != null) {
                heartbeat.close();
            }
        }
    }

    /**
     * 抢占同步部署的状态（CAS 到 deploying）
     * 与异步 worker 的 {@link #claimDeploying} 共用"条件更新"语义：谁先抢到谁构建。
     * 历史数据可能没有 {@code deploy_status}，此时用 {@code deploy_status IS NULL} 做条件更新……
     */
    private boolean claimSyncDeploying(Long appId, String expectedStatus, Long operatorId, String taskId) {
        return claimDeployStatus(appId, expectedStatus, DeployStatusEnum.DEPLOYING, operatorId, taskId);
    }

    /**
     * 把部署状态从"读到的旧状态"CAS 到目标状态
     * 异步提交（-&gt; queued）与同步/异步构建（-&gt; deploying）共用这一个入口，
     * 保证两边同时看到同一个旧状态时只有一方能更新成功，不会互相覆盖……
     */
    private boolean claimDeployStatus(Long appId, String expectedStatus, DeployStatusEnum target,
                                      Long operatorId, String taskId) {
        App claim = deployState(target, "", operatorId, taskId);
        claim.setDeployHeartbeat(LocalDateTime.now());
        QueryCondition condition = appCondition(appId);
        if (expectedStatus == null) {
            condition.and(new QueryColumn("deploy_status").isNull(true));
        } else {
            condition.and(QueryCondition.create(new QueryColumn("deploy_status"), expectedStatus));
        }
        return this.getMapper().updateByCondition(claim, condition) == 1;
    }

    /**
     * 查询部署状态（异步部署的轮询接口）
     * 状态来源优先级：deploy_status 字段 > deployKey 是否存在。
     * 改造前的历史数据没有 deploy_status，只要已经有 deployKey 就视为部署完成，前端无需区分。
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
     * 与同步接口的差异：
     * 不在请求线程执行构建；用状态 CAS 保证同一应用只有一个部署任务，抢不到直接返回当前状态。
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

        // 抢占部署资格：在 per-app 锁内完成"检查 + 落状态"。
        // 落状态用 CAS（从刚才读到的旧状态更新），而不是无条件 updateById：
        // 同步部署也可能刚从同一个旧状态抢到 deploying，无条件写会把它的状态覆盖掉。
        // per-app 锁只减少本实例内的竞争，真正的互斥由状态 CAS 保证（多实例同样成立）。
        LockRef submitLockRef = acquireSubmitLock(appId);
        long sequence;
        try {
            submitLockRef.lock.lock();
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
                // 每次提交生成独立 taskId：消息和数据库用同一个 token，旧消息迟到时无法抢占新任务
                String taskId = UUID.randomUUID().toString();
                // 先落 queued：真正的构建由队列 worker 按并发上限调度
                if (!claimDeployStatus(appId, latest.getDeployStatus(), DeployStatusEnum.QUEUED,
                        loginUser.getId(), taskId)) {
                    throw new BusinessException(ErrorCode.TOO_MANY_REQUESTS,
                            "部署状态已变化（可能有其他部署任务正在进行），请稍后重试");
                }
                try {
                    sequence = deployQueueManager.submit(appId, loginUser.getId(), taskId);
                } catch (RuntimeException e) {
                    // 条件回滚：只把"仍是我们刚写的 queued"改回 failed。
                    // 若 worker 已经抢到 deploying，说明消息其实到了，不能覆盖它的状态。
                    // 注意保留 taskId：确认超时但消息可能已入队时，worker 凭同一 token 仍可恢复。
                    boolean rolledBack = false;
                    try {
                        rolledBack = rollbackQueuedToFailed(appId, taskId, resolveDeployErrorMessage(e));
                    } catch (RuntimeException rollbackError) {
                        // 回滚本身失败（例如数据库抖动）：保留原始异常，别让用户看到一个无关的数据库错误
                        e.addSuppressed(rollbackError);
                        log.error("部署提交失败后回滚状态出错，appId: {}, taskId: {}", appId, taskId, rollbackError);
                    }
                    if (!rolledBack) {
                        log.warn("部署提交失败但状态已被 worker 接管或回滚未命中，appId: {}, taskId: {}", appId, taskId);
                    }
                    throw e;
                }
                log.info("已受理异步部署任务，appId: {}, 发起人: {}, 排队序号: {}, taskId: {}",
                        appId, loginUser.getId(), sequence, taskId);
            } finally {
                submitLockRef.lock.unlock();
            }
        } finally {
            releaseSubmitLock(appId, submitLockRef);
        }

        Integer queuePosition = computeQueuePosition(appId);
        String message = queuePosition == null
                ? "部署任务已提交，正在构建中，请稍后轮询部署状态"
                : "部署任务已进入队列（当前第 " + queuePosition + " 位），等待构建中";
        return DeployStatusVO.accepted(appId, DeployStatusEnum.QUEUED, message, queuePosition);
    }

    /**
     * 队列 worker 回调：真正执行构建与发布
     * 状态流转：queued -> deploying（开始构建）-> ready / failed。
     * 异常语义（决定消息是 ACK 还是重投）……
     */
    public void handleDeployTask(DeployQueueManager.DeployTask task) {
        Long appId = task.appId();
        String taskId = task.taskId();
        int attempt = task.attempt();
        boolean redelivered = task.redelivered();
        String threadName = Thread.currentThread().getName();
        boolean claimed = false;
        try {
            App latest = this.getById(appId);
            if (latest == null) {
                log.warn("部署任务对应的应用已不存在，appId: {}, sequence: {}", appId, task.sequence());
                return;
            }
            // 幂等抢占：按 taskId 做 CAS。
            // - 同一任务崩溃后重投（redelivered=true）：状态可能已是 deploying，允许继续执行；
            // - 同一任务的基础设施重投（attempt>0）：可能停在 deploying（回退失败），也允许恢复；
            // - 新任务：taskId 不匹配，旧消息永远抢不到状态。
            if (!claimDeploying(appId, taskId, attempt, redelivered)) {
                log.info("部署任务已被处理或状态已变化，跳过（幂等），appId: {}, sequence: {}, taskId: {}, thread: {}",
                        appId, task.sequence(), taskId, threadName);
                return;
            }
            claimed = true;
            log.info("部署任务开始构建，appId: {}, sequence: {}, taskId: {}, 重试次数: {}, 重投: {}, thread: {}",
                    appId, task.sequence(), taskId, attempt, redelivered, threadName);

            DeployHeartbeat.Handle heartbeat = deployHeartbeat.start(appId, taskId);
            try {
                String sourceDirPath = buildSourceDirPath(latest.getCodeGenType(), appId);
                File sourceDir = new File(sourceDirPath);

                LockRef lockRef = acquireDeployLock(appId);
                String deployUrl;
                try {
                    lockRef.lock.lock();
                    try {
                        deployUrl = doDeploy(latest, appId, latest.getCodeGenType(), sourceDir);
                    } finally {
                        lockRef.lock.unlock();
                    }
                } finally {
                    releaseDeployLock(appId, lockRef);
                }

                // doDeploy 成功时已经把状态写成 ready，这里不再重复 update：
                // 重复写一次只会多一次数据库往返，且写失败时容易被误判成"部署失败"
                log.info("部署任务完成，appId: {}, sequence: {}, 部署地址: {}, thread: {}", appId, task.sequence(), deployUrl, threadName);
            } finally {
                if (heartbeat != null) {
                    heartbeat.close();
                }
            }
        } catch (BusinessException e) {
            String errorMessage = resolveDeployErrorMessage(e);
            log.error("部署任务失败（业务），appId: {}, sequence: {}, thread: {}, error: {}",
                    appId, task.sequence(), threadName, errorMessage);
            if (claimed) {
                // 落库本身抛异常时说明数据库也不可用，异常会继续向外传播 → 走基础设施重投
                markDeployFailed(appId, errorMessage);
            } else {
                log.warn("部署任务在抢占状态前失败，直接丢弃消息，appId: {}, sequence: {}", appId, task.sequence());
            }
        } catch (RuntimeException e) {
            // 基础设施异常：消费者会重投。尽量把状态退回 queued，让重投能再次抢到构建权；
            // 即使回退失败，重投消息也会凭同一 taskId 从 deploying 重新抢占（见 claimDeploying）
            log.error("部署任务消费异常（基础设施），将交给 MQ 重投，appId: {}, sequence: {}, taskId: {}, thread: {}",
                    appId, task.sequence(), taskId, threadName, e);
            if (claimed) {
                resetDeployingToQueued(appId);
            }
            throw e;
        } catch (Error e) {
            // 不吞 OOM / ThreadDeath：状态留给 30 分钟僵死判定，严重错误交给容器处理
            log.error("部署任务发生严重错误，appId: {}, sequence: {}, thread: {}",
                    appId, task.sequence(), threadName, e);
            throw e;
        }
    }

    /**
     * 死信回调：把应用落为 failed，让用户可以立即重新提交
     * 只更新"仍属于这个 taskId 且还停在 queued/deploying"的行：
     * 用户已重新提交（新 taskId）或已经部署完成（ready）时不会被旧消息覆盖。
     */
    @Override
    public void handleDeadLetter(DeployQueueManager.DeployTask task) {
        Long appId = task.appId();
        if (appId == null) {
            return;
        }
        App failed = deployState(DeployStatusEnum.FAILED, "部署任务重试耗尽，已转入死信队列，请重新提交", null, "");
        QueryCondition condition = appCondition(appId)
                .and(new QueryColumn("deploy_status").in(
                        List.of(DeployStatusEnum.QUEUED.getValue(), DeployStatusEnum.DEPLOYING.getValue())));
        if (StrUtil.isNotBlank(task.taskId())) {
            condition.and(QueryCondition.create(new QueryColumn("deploy_task_id"), task.taskId()));
        } else {
            // 升级前的旧消息没有 taskId：只处理还没有新任务 token 的行
            condition.and(new QueryColumn("deploy_task_id").isNull(true));
        }
        int affected = this.getMapper().updateByCondition(failed, condition);
        if (affected == 0) {
            log.warn("死信任务对应的状态已变化（可能已重新提交或完成），跳过落库，appId: {}, taskId: {}",
                    appId, task.taskId());
        } else {
            log.warn("死信任务已落为 failed，appId: {}, taskId: {}，用户可直接重新提交", appId, task.taskId());
        }
    }

    /**
     * 抢占"开始构建"的状态（-&gt; deploying），按 taskId 做幂等
     * 规则（taskId 非空时）：
     * 同一 token 到达时应当继续执行；</li>……
     */
    private boolean claimDeploying(Long appId, String taskId, int attempt, boolean redelivered) {
        App claim = deployState(DeployStatusEnum.DEPLOYING, "", null, null);
        claim.setDeployHeartbeat(LocalDateTime.now());
        QueryCondition condition = appCondition(appId);
        if (StrUtil.isBlank(taskId)) {
            // 兼容升级前已在队列里的消息
            if (attempt > 0 || redelivered) {
                condition.and(new QueryColumn("deploy_status").in(
                        List.of(DeployStatusEnum.QUEUED.getValue(), DeployStatusEnum.DEPLOYING.getValue())));
            } else {
                condition.and(QueryCondition.create(new QueryColumn("deploy_status"),
                        DeployStatusEnum.QUEUED.getValue()));
            }
            return this.getMapper().updateByCondition(claim, condition) == 1;
        }
        condition.and(QueryCondition.create(new QueryColumn("deploy_task_id"), taskId));
        List<String> allowedStatuses = new ArrayList<>(List.of(
                DeployStatusEnum.QUEUED.getValue(),
                DeployStatusEnum.FAILED.getValue()));
        if (redelivered || attempt > 0) {
            allowedStatuses.add(DeployStatusEnum.DEPLOYING.getValue());
        }
        condition.and(new QueryColumn("deploy_status").in(allowedStatuses));
        return this.getMapper().updateByCondition(claim, condition) == 1;
    }

    /**
     * 把"部署中"退回"排队中"（仅用于基础设施异常后的重投准备）
     * 失败也不抛出：此时数据库本来就可能不可用，重投消息会凭 {@code attempt} 从 deploying 重新抢占。
     */
    private void resetDeployingToQueued(Long appId) {
        try {
            App reset = deployState(DeployStatusEnum.QUEUED, "", null, null);
            reset.setDeployHeartbeat(LocalDateTime.now());
            QueryCondition condition = appCondition(appId)
                    .and(QueryCondition.create(new QueryColumn("deploy_status"),
                            DeployStatusEnum.DEPLOYING.getValue()));
            int affected = this.getMapper().updateByCondition(reset, condition);
            if (affected == 0) {
                log.warn("回退部署状态时状态已变化，appId: {}", appId);
            } else {
                log.info("基础设施异常，已把部署状态退回 queued，等待 MQ 重投，appId: {}", appId);
            }
        } catch (RuntimeException e) {
            log.error("回退部署状态失败（数据库可能不可用），重投时将通过 attempt 重新抢占，appId: {}", appId, e);
        }
    }

    /**
     * 把部署状态落为失败（仅当仍处于 deploying 时生效）
     * 条件更新避免"旧任务的失败结果覆盖新任务状态"；同时清空 taskId，
     * 防止迟到的同一任务消息（例如发布确认超时后的重复消息）把已经确认失败的任务重新跑起来……
     */
    private void markDeployFailed(Long appId, String errorMessage) {
        // 置空串而不是 null：MyBatis-Flex 默认忽略 null 字段，空串能确保条件 taskId 不再匹配
        App failed = deployState(DeployStatusEnum.FAILED, errorMessage, null, "");
        QueryCondition condition = appCondition(appId)
                .and(QueryCondition.create(new QueryColumn("deploy_status"),
                        DeployStatusEnum.DEPLOYING.getValue()));
        int affected = this.getMapper().updateByCondition(failed, condition);
        if (affected == 0) {
            log.warn("写入部署失败状态时状态已变化，跳过覆盖，appId: {}", appId);
        }
    }

    /**
     * 提交流程失败时把 queued 回滚为 failed（条件更新，只回滚仍属于本任务的 queued）
     * 保留 taskId 是有意为之：publisher confirm 超时时消息可能已经入队，
     * worker 凭同一 token 仍可从 failed 恢复并构建；若消息没到，用户可直接重新提交……
     */
    private boolean rollbackQueuedToFailed(Long appId, String taskId, String errorMessage) {
        App rollback = deployState(DeployStatusEnum.FAILED, errorMessage, null, null);
        QueryCondition condition = appCondition(appId)
                .and(QueryCondition.create(new QueryColumn("deploy_status"),
                        DeployStatusEnum.QUEUED.getValue()))
                .and(QueryCondition.create(new QueryColumn("deploy_task_id"), taskId));
        return this.getMapper().updateByCondition(rollback, condition) == 1;
    }

    /**
     * 计算排队位置（从 1 开始）
     * 消息进队后就归 broker 管了，进程内拿不到"这条消息在队列里排第几"。但用户看到的
     * "前面还有几个"只需要一个近似值：统计状态仍为 queued、且更新时间早于本应用的任务数即可……
     */
    private Integer computeQueuePosition(Long appId) {
        return computeQueuePosition(this.getById(appId));
    }

    /** 计算排队位置（已经拿到应用实体时用这个重载，省一次查询） */
    private Integer computeQueuePosition(App app) {
        if (app == null || app.getUpdateTime() == null
                || !DeployStatusEnum.QUEUED.getValue().equals(app.getDeployStatus())) {
            return null;
        }
        QueryWrapper queryWrapper = QueryWrapper.create()
                .eq("deploy_status", DeployStatusEnum.QUEUED.getValue())
                .lt("update_time", app.getUpdateTime());
        long ahead = this.getMapper().selectCountByQuery(queryWrapper);
        return (int) ahead + 1;
    }

    /** 用中文描述应用当前部署状态（用于"未受理"提示） */
    private String describeDeployStatus(App app) {
        DeployStatusEnum status = DeployStatusEnum.getEnumByValue(app.getDeployStatus());
        if (status == null) {
            return StrUtil.isNotBlank(app.getDeployKey()) ? "已部署" : "未部署";
        }
        return status.getText();
    }

    /**
     * 判断应用当前是否可以受理新的部署任务
     * 可受理：从未部署（状态为空）、空闲、上次失败，或"排队中/部署中"但已经超过 {@link #DEPLOY_STALE_MINUTES}
     * 没有动静（进程重启/任务异常退出留下的僵死状态）。
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
     * 状态机准入校验：不可提交时抛出带"当前状态"的业务异常
     * 同步/异步提交共用同一套提示；排队中/部署中属于"忙"（429），
     * 已部署且代码未变属于"本次操作无意义"（50001）。
     */
    private void ensureDeploySubmittable(App app) {
        if (app == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "应用不存在");
        }
        if (isDeploySubmittable(app)) {
            return;
        }
        DeployStatusEnum current = DeployStatusEnum.getEnumByValue(app.getDeployStatus());
        ErrorCode code = current == DeployStatusEnum.QUEUED || current == DeployStatusEnum.DEPLOYING
                ? ErrorCode.TOO_MANY_REQUESTS : ErrorCode.OPERATION_ERROR;
        throw new BusinessException(code,
                "部署任务未被受理：当前状态为 " + describeDeployStatus(app) + "，未启动新的部署任务");
    }

    /**
     * 已部署的产物是否落后于当前代码（即"代码改过、需要重新部署"）
     * 判定依据是应用的编辑时间晚于最近一次部署完成时间：
     * 避免打开页面就显示"可重新部署"。</li>
     */
    private boolean isDeployedContentStale(App app) {
        if (app == null || app.getEditTime() == null || app.getDeployedTime() == null) {
            return false;
        }
        return app.getEditTime().isAfter(app.getDeployedTime());
    }

    /** 根据应用当前数据推导部署状态视图 */
    private DeployStatusVO buildDeployStatus(App app) {
        DeployStatusEnum status = DeployStatusEnum.getEnumByValue(app.getDeployStatus());
        // 历史数据没有状态字段：只要有 deployKey 就视为部署完成
        if (status == null) {
            if (StrUtil.isNotBlank(app.getDeployKey())) {
                DeployStatusVO vo = DeployStatusVO.ready(app.getId(), buildDeployUrl(app.getDeployKey()), app.getDeployedTime());
                fillDeployStale(vo, app);
                return vo;
            }
            return DeployStatusVO.of(app.getId(), DeployStatusEnum.IDLE, DeployStatusEnum.IDLE.getMessage(), null);
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
            case QUEUED -> DeployStatusVO.queued(app.getId(), computeQueuePosition(app),
                    deployQueueManager.queueSize(), deployQueueManager.getWorkerCount());
            case DEPLOYING -> DeployStatusVO.of(app.getId(), DeployStatusEnum.DEPLOYING,
                    DeployStatusEnum.DEPLOYING.getMessage(), null);
            case FAILED -> DeployStatusVO.of(app.getId(), DeployStatusEnum.FAILED,
                    DeployStatusEnum.FAILED.getMessage(), app.getDeployError());
            case IDLE -> DeployStatusVO.of(app.getId(), DeployStatusEnum.IDLE,
                    DeployStatusEnum.IDLE.getMessage(), null);
        };
    }

    /** 部署状态更新的公共实体 */
    private static App deployState(DeployStatusEnum status, String error, Long operatorId, String taskId) {
        App app = new App();
        app.setDeployStatus(status.getValue());
        app.setDeployError(error);
        app.setDeployOperatorId(operatorId);
        app.setDeployTaskId(taskId);
        return app;
    }

    /** 按应用 id 的基础更新条件 */
    private static QueryCondition appCondition(Long appId) {
        return QueryCondition.create(new QueryColumn("id"), appId);
    }

    /** 把"是否需要重新部署"写进状态视图 */
    private boolean fillDeployStale(DeployStatusVO vo, App app) {
        boolean stale = isDeployedContentStale(app);
        vo.setDeployStale(stale);
        return stale;
    }

    /**
     * 判断当前用户是否有权操作该应用的部署
     * 除了应用创建者，异步部署的发起人也允许查看状态：任务已经挂到后台，
     * 如果只认创建者，管理员/协作者发起部署后就无法跟踪进度。
     */
    private boolean isDeployOperator(App app, User loginUser) {
        if (app == null || loginUser == null) {
            return false;
        }
        Long loginUserId = loginUser.getId();
        return loginUserId.equals(app.getUserId()) || loginUserId.equals(app.getDeployOperatorId());
    }

    /** 构造部署地址 */
    private String buildDeployUrl(String deployKey) {
        return AppPathUtils.deployUrl(deployHost, deployKey);
    }

    /** 构造源码目录路径 */
    private String buildSourceDirPath(String codeGenType, Long appId) {
        return AppPathUtils.projectDirPath(codeGenType, appId);
    }

    /**
     * 查询应用并校验归属（仅允许创建者）
     */
    private App requireOwnedApp(Long appId, User loginUser) {
        App app = this.getById(appId);
        if (app == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "应用不存在");
        }
        if (!app.getUserId().equals(loginUser.getId())) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "无权限访问该应用");
        }
        return app;
    }

    /**
     * 是否为 Vue 工程模式
     */
    private static boolean isVueProject(String codeGenType) {
        return CodeGenTypeEnum.VUE_PROJECT == CodeGenTypeEnum.getEnumByValue(codeGenType);
    }

    /** 把部署异常转换成面向用户的失败原因（不含堆栈，长度受限） */
    private String resolveDeployErrorMessage(Throwable e) {
        String message = e == null ? null : e.getMessage();
        if (StrUtil.isBlank(message)) {
            message = e == null ? "部署失败" : e.getClass().getSimpleName();
        }
        return StrUtil.maxLength(message, DEPLOY_ERROR_MAX_LENGTH);
    }

    /** 部署主体：CAS 抢占 deployKey -> 构建 -> 复制到部署目录 -> 提交部署信息 */
    protected String doDeploy(App app, Long appId, String codeGenType, File sourceDir) {

        String deployKey = claimDeployKey(appId, app.getDeployKey());
        String deployDirPath = AppConstant.CODE_DEPLOY_ROOT_DIR + File.separator + deployKey;

        File publishDir = sourceDir;
        Path stagingDir = null;
        try {
            if (isVueProject(codeGenType)) {
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

            try {
                FileUtil.copyContent(publishDir, new File(deployDirPath), true);
            } catch (Exception e) {
                releaseDeployKeyOnFailure(appId, deployKey);
                log.error("部署应用失败，appId: {}", appId, e);
                throw new BusinessException(ErrorCode.SYSTEM_ERROR, "部署应用失败：" + e.getMessage());
            }

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

            String appDeployUrl = buildDeployUrl(deployKey);
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
     * 关键点：用条件更新 {@code WHERE id=? AND deploy_key IS NULL} 保证只有一个并发请求写入成功，
     * 其它请求（包括"别人正在部署同一个应用"）直接复用数据库里已有的 key。
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
                QueryCondition casCondition = appCondition(appId)
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

    /** 部署失败时释放 deployKey（回滚为 NULL），让下一次部署可以复用同一条路径重试 */
    private void releaseDeployKeyOnFailure(Long appId, String deployKey) {
        if (StrUtil.isBlank(deployKey)) {
            return;
        }
        try {
            // 用 UpdateWrapper 显式把列置为 SQL NULL（普通 set(null) 会被当作"不更新该字段"）
            App release = UpdateWrapper
                    .of(new App())
                    .setRaw("deploy_key", "NULL")
                    .toEntity();
            QueryCondition condition = appCondition(appId)
                    .and(QueryCondition.create(new QueryColumn("deploy_key"), deployKey));
            int affected = this.getMapper().updateByCondition(release, condition);
            log.warn("部署失败，已释放 deployKey，appId: {}, deployKey: {}, affected: {}", appId, deployKey, affected);
        } catch (Exception e) {
            log.error("部署失败后释放 deployKey 出错，appId: {}, deployKey: {}", appId, deployKey, e);
        }
    }

    /** 创建构建暂存目录（与源码目录同级，保证后续 rename 在同一个分区内，才能真正原子） */
    private Path createBuildStagingDir(File sourceDir, Long appId) {
        Path staging = sourceDir.toPath().resolveSibling(BUILD_STAGING_PREFIX + appId + "-" + System.nanoTime());
        FileUtil.mkdir(staging.toFile());
        try {
            // 只复制构建需要的源码，跳过上一次构建留下的 node_modules / dist（原因见 STAGING_SKIP_DIRS：
            // 把 node_modules 带过去会让 Linux 上的 .bin/* 符号链接被实体化，构建必然失败）
            copyProjectToStaging(sourceDir.toPath(), staging);
        } catch (Exception e) {
            FileUtil.del(staging.toFile());
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "准备构建暂存目录失败：" + e.getMessage());
        }
        return staging;
    }

    /**
     * 把工程源码复制到暂存目录：跳过工程根目录下的 {@code node_modules} 与 {@code dist}
     * 不用 {@code FileUtil.copyContent} 的原因：它无法按目录名过滤，而这两个目录又必须排除
     * （见 {@link #STAGING_SKIP_DIRS}）。只跳过根目录下的同名目录，内层同名目录照常复制。
     */
    private void copyProjectToStaging(Path sourceDir, Path stagingDir) throws IOException {
        Files.walkFileTree(sourceDir, new SimpleFileVisitor<>() {

            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Path relative = sourceDir.relativize(dir);
                if (relative.getNameCount() == 1 && STAGING_SKIP_DIRS.contains(relative.toString())) {
                    log.debug("构建暂存目录跳过: {}", dir);
                    return FileVisitResult.SKIP_SUBTREE;
                }
                Files.createDirectories(stagingDir.resolve(relative));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.copy(file, stagingDir.resolve(sourceDir.relativize(file)), StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /**
     * 用新目录替换目标目录（先备份旧目录，失败时回滚）
     * 目标：Windows 下 {@code Files.move} 无法覆盖已存在目录，因此采用
     * "旧目录改名备份 -> 新目录改名就位 -> 删除备份" 的顺序，任何一步失败都恢复旧目录。
     */
    private void replaceDirectory(Path newDir, Path targetDir) throws IOException {
        Path parent = targetDir.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
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

    /** 目录移动：优先用原子 rename，跨分区场景退化为复制 + 删除 */
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

    /** 异步生成截图并上传到COS，更新应用封面 */
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

    /** 删除应用，并关联删除该应用的所有对话历史 */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean deleteApp(Long appId) {

        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR, "应用ID不合法");

        boolean appResult = this.removeById(appId);
        ThrowUtils.throwIf(!appResult, ErrorCode.SYSTEM_ERROR, "删除应用失败");

        //    注意：这里不再用返回值判断成败——"该应用没有历史"是正常情况，
        //    deleteByAppId 已把它视为成功（详见该方法注释）；真正的数据库异常会直接抛出并回滚
        chatHistoryService.deleteByAppId(appId);
        return true;
    }
}
