package com.example.aicodebackend.controller;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.example.aicodebackend.ai.model.message.ErrorMessage;
import com.example.aicodebackend.annotation.AuthCheck;
import com.example.aicodebackend.common.BaseResponse;
import com.example.aicodebackend.common.DeleteRequest;
import com.example.aicodebackend.common.PageUtils;
import com.example.aicodebackend.common.ResultUtils;
import com.example.aicodebackend.constant.AppConstant;
import com.example.aicodebackend.exception.BusinessException;
import com.example.aicodebackend.exception.ErrorCode;
import com.example.aicodebackend.exception.ThrowUtils;
import com.example.aicodebackend.model.dto.app.*;
import com.example.aicodebackend.model.entity.App;
import com.example.aicodebackend.model.entity.User;
import com.example.aicodebackend.model.enums.AIModelTypeEnum;
import com.example.aicodebackend.model.enums.CodeGenTypeEnum;
import com.example.aicodebackend.model.enums.UserRoleEnum;
import com.example.aicodebackend.core.generation.GenerationTaskRegistry;
import com.example.aicodebackend.model.vo.AppVO;
import com.example.aicodebackend.model.vo.DeployStatusVO;
import com.example.aicodebackend.model.vo.GenerationStatusVO;
import com.example.aicodebackend.service.AppService;
import com.example.aicodebackend.service.ProjectDownloadService;
import com.example.aicodebackend.service.UserService;
import com.example.aicodebackend.utils.AppPathUtils;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.File;
import java.time.LocalDateTime;

@Slf4j
@RestController
@RequestMapping("/app")
public class AppController {

    @Resource
    private AppService appService;

    @Resource
    private UserService userService;

    @Resource
    private ProjectDownloadService projectDownloadService;

    /** 用户创建应用（只需填写初始化 prompt，应用名称和代码生成类型由系统自动生成） */
    @PostMapping("/add")
    public BaseResponse<Long> addApp(@RequestBody AppAddRequest appAddRequest, HttpServletRequest request) {

        ThrowUtils.throwIf(appAddRequest == null, ErrorCode.PARAMS_ERROR);
        String initPrompt = appAddRequest.getInitPrompt();
        ThrowUtils.throwIf(StrUtil.isBlank(initPrompt), ErrorCode.PARAMS_ERROR, "初始化 prompt 不能为空");

        User loginUser = userService.getLoginUser(request);

        CodeGenTypeEnum codeGenTypeEnum = CodeGenTypeEnum.getEnumByValue(appAddRequest.getCodeGenType());
        ThrowUtils.throwIf(codeGenTypeEnum == null, ErrorCode.PARAMS_ERROR, "不支持的代码生成类型");

        AIModelTypeEnum aiModelTypeEnum = AIModelTypeEnum.getEnumByValue(appAddRequest.getAiModelType());
        ThrowUtils.throwIf(aiModelTypeEnum == null, ErrorCode.PARAMS_ERROR, "不支持的 AI 模型类型");

        App app = new App();
        BeanUtil.copyProperties(appAddRequest, app);
        app.setUserId(loginUser.getId());
        // 应用名称由系统自动生成：取初始化提示词的前 12 位（压缩空白字符，避免名称中出现换行）
        app.setAppName(generateAppName(initPrompt));
        // 代码生成类型与 AI 模型类型由用户指定（未指定时已在请求体中回落到默认值）
        app.setCodeGenType(codeGenTypeEnum.getValue());
        app.setAiModelType(aiModelTypeEnum.getValue());

        boolean result = appService.save(app);
        ThrowUtils.throwIf(!result, ErrorCode.SYSTEM_ERROR, "创建应用失败");
        return ResultUtils.success(app.getId());
    }

    /** 根据初始化提示词自动生成应用名称（取前 12 位） */
    private String generateAppName(String initPrompt) {
        String trimmedPrompt = initPrompt.replaceAll("\\s+", " ").trim();
        return StrUtil.sub(trimmedPrompt, 0, AppConstant.APP_NAME_MAX_LENGTH);
    }

    /** 用户修改自己的应用（目前只支持修改应用名称） */
    @PostMapping("/update")
    public BaseResponse<Boolean> updateApp(@RequestBody AppUpdateRequest appUpdateRequest, HttpServletRequest request) {

        ThrowUtils.throwIf(appUpdateRequest == null, ErrorCode.PARAMS_ERROR);
        Long id = appUpdateRequest.getId();
        ThrowUtils.throwIfInvalidId(id, "应用ID");
        ThrowUtils.throwIf(StrUtil.isBlank(appUpdateRequest.getAppName()), ErrorCode.PARAMS_ERROR, "应用名称不能为空");

        User loginUser = userService.getLoginUser(request);

        App oldApp = appService.getById(id);
        ThrowUtils.throwIf(oldApp == null, ErrorCode.NOT_FOUND_ERROR, "应用不存在");

        ThrowUtils.throwIf(!oldApp.getUserId().equals(loginUser.getId()), ErrorCode.NO_AUTH_ERROR, "无权限修改该应用");

        App app = new App();
        app.setId(id);
        app.setAppName(appUpdateRequest.getAppName());
        app.setEditTime(LocalDateTime.now());
        boolean result = appService.updateById(app);
        ThrowUtils.throwIf(!result, ErrorCode.SYSTEM_ERROR, "更新应用失败");
        return ResultUtils.success(true);
    }

    /** 用户删除自己的应用 */
    @PostMapping("/delete")
    public BaseResponse<Boolean> deleteApp(@RequestBody DeleteRequest deleteRequest, HttpServletRequest request) {

        ThrowUtils.throwIf(deleteRequest == null || deleteRequest.getId() == null, ErrorCode.PARAMS_ERROR);
        Long id = deleteRequest.getId();

        User loginUser = userService.getLoginUser(request);

        App oldApp = appService.getById(id);
        ThrowUtils.throwIf(oldApp == null, ErrorCode.NOT_FOUND_ERROR, "应用不存在");

        ThrowUtils.throwIf(!oldApp.getUserId().equals(loginUser.getId())
                        && !UserRoleEnum.isAdmin(loginUser.getUserRole()),
                ErrorCode.NO_AUTH_ERROR, "无权限删除该应用");

        boolean result = appService.deleteApp(id);
        ThrowUtils.throwIf(!result, ErrorCode.SYSTEM_ERROR, "删除应用失败");
        return ResultUtils.success(true);
    }

    /** 根据ID获取应用详情VO */
    @GetMapping("/get/vo")
    public BaseResponse<AppVO> getAppVOById(@RequestParam Long id) {

        ThrowUtils.throwIfInvalidId(id, "应用ID");

        App app = appService.getById(id);
        ThrowUtils.throwIf(app == null, ErrorCode.NOT_FOUND_ERROR, "应用不存在");

        AppVO appVO = appService.getAppVO(app);
        return ResultUtils.success(appVO);
    }

    /** 分页获取当前用户自己的应用列表（支持根据名称查询，每页最多 20 个） */
    @PostMapping("/my/list/page/vo")
    public BaseResponse<Page<AppVO>> listMyAppVOByPage(@RequestBody AppQueryRequest appQueryRequest, HttpServletRequest request) {

        ThrowUtils.throwIf(appQueryRequest == null, ErrorCode.PARAMS_ERROR, "查询参数为空");

        long pageNum = appQueryRequest.getPageNum();
        long pageSize = appQueryRequest.getPageSize();
        PageUtils.validate(pageNum, pageSize, PageUtils.DEFAULT_MAX_PAGE_SIZE, "每页最多查询 20 个应用");
        User loginUser = userService.getLoginUser(request);
        appQueryRequest.setUserId(loginUser.getId());
        QueryWrapper queryWrapper = appService.getQueryWrapper(appQueryRequest);
        Page<App> appPage = appService.page(Page.of(pageNum, pageSize), queryWrapper);
        return ResultUtils.success(PageUtils.toVoPage(appPage, pageNum, pageSize, appService::getAppVOList));
    }

    /** 分页获取精选应用列表（支持根据名称查询，每页最多 20 个） */
    @PostMapping("/good/list/page/vo")
    public BaseResponse<Page<AppVO>> listGoodAppVOByPage(@RequestBody AppQueryRequest appQueryRequest) {

        ThrowUtils.throwIf(appQueryRequest == null, ErrorCode.PARAMS_ERROR, "查询参数为空");

        long pageNum = appQueryRequest.getPageNum();
        long pageSize = appQueryRequest.getPageSize();
        PageUtils.validate(pageNum, pageSize, PageUtils.DEFAULT_MAX_PAGE_SIZE, "每页最多查询 20 个应用");
        appQueryRequest.setPriority(AppConstant.GOOD_APP_PRIORITY);
        QueryWrapper queryWrapper = appService.getQueryWrapper(appQueryRequest);
        Page<App> appPage = appService.page(Page.of(pageNum, pageSize), queryWrapper);
        return ResultUtils.success(PageUtils.toVoPage(appPage, pageNum, pageSize, appService::getAppVOList));
    }

    /** 根据ID删除任意应用（仅管理员可用） */
    @PostMapping("/admin/delete")
    @AuthCheck(mustRole = UserRoleEnum.ADMIN_VALUE)
    public BaseResponse<Boolean> deleteAppByAdmin(@RequestBody DeleteRequest deleteRequest) {

        ThrowUtils.throwIf(deleteRequest == null || deleteRequest.getId() == null, ErrorCode.PARAMS_ERROR);
        Long id = deleteRequest.getId();

        App oldApp = appService.getById(id);
        ThrowUtils.throwIf(oldApp == null, ErrorCode.NOT_FOUND_ERROR, "应用不存在");

        appService.deleteApp(id);
        return ResultUtils.success(true);
    }

    /** 根据ID更新任意应用（仅管理员可用，支持更新应用名称、应用封面、优先级） */
    @PostMapping("/admin/update")
    @AuthCheck(mustRole = UserRoleEnum.ADMIN_VALUE)
    public BaseResponse<Boolean> updateAppByAdmin(@RequestBody AppAdminUpdateRequest appAdminUpdateRequest) {

        ThrowUtils.throwIf(appAdminUpdateRequest == null, ErrorCode.PARAMS_ERROR);
        Long id = appAdminUpdateRequest.getId();
        ThrowUtils.throwIfInvalidId(id, "应用ID");

        App oldApp = appService.getById(id);
        ThrowUtils.throwIf(oldApp == null, ErrorCode.NOT_FOUND_ERROR, "应用不存在");

        App app = new App();
        BeanUtil.copyProperties(appAdminUpdateRequest, app);
        app.setEditTime(LocalDateTime.now());
        boolean result = appService.updateById(app);
        ThrowUtils.throwIf(!result, ErrorCode.SYSTEM_ERROR, "更新应用失败");
        return ResultUtils.success(true);
    }

    /** 分页获取应用信息列表（仅管理员可用，支持根据除时间外的任何字段查询，每页数量不限） */
    @PostMapping("/admin/list/page/vo")
    @AuthCheck(mustRole = UserRoleEnum.ADMIN_VALUE)
    public BaseResponse<Page<AppVO>> listAppVOByPageByAdmin(@RequestBody AppQueryRequest appQueryRequest) {

        ThrowUtils.throwIf(appQueryRequest == null, ErrorCode.PARAMS_ERROR, "查询参数为空");

        long pageNum = appQueryRequest.getPageNum();
        long pageSize = appQueryRequest.getPageSize();
        PageUtils.validate(pageNum, pageSize);
        QueryWrapper queryWrapper = appService.getQueryWrapper(appQueryRequest);
        Page<App> appPage = appService.page(Page.of(pageNum, pageSize), queryWrapper);
        return ResultUtils.success(PageUtils.toVoPage(appPage, pageNum, pageSize, appService::getAppVOList));
    }

    /** 根据ID获取任意应用详情VO（仅管理员可用） */
    @GetMapping("/admin/get/vo")
    @AuthCheck(mustRole = UserRoleEnum.ADMIN_VALUE)
    public BaseResponse<AppVO> getAppVOByIdByAdmin(@RequestParam Long id) {

        ThrowUtils.throwIfInvalidId(id, "应用ID");

        App app = appService.getById(id);
        ThrowUtils.throwIf(app == null, ErrorCode.NOT_FOUND_ERROR, "应用不存在");

        AppVO appVO = appService.getAppVO(app);
        return ResultUtils.success(appVO);
    }

    /**
     * 根据应用ID和用户输入的 prompt，调用服务生成代码，并通过 SSE 流式返回生成的代码块
     * 接口契约：本接口始终以 SSE（text/event-stream）形式响应，任何失败都以 type=error 的数据帧下发，
     * 不使用 HTTP 错误码或 JSON 错误体。这样做的原因是：SSE 响应一旦按 text/event-stream 协商……
     */
    @GetMapping(value = "/chat/gen/code", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> chatToGenCode(@RequestParam Long appId, @RequestParam String prompt, HttpServletRequest request) {
        Flux<GenerationTaskRegistry.SequencedFrame> chatToGenCodeFlux;
        try {

            ThrowUtils.throwIfInvalidId(appId, "应用ID");
            ThrowUtils.throwIf(StrUtil.isBlank(prompt), ErrorCode.PARAMS_ERROR, "prompt不能为空");

            User loginUser = userService.getLoginUser(request);

            chatToGenCodeFlux = appService.chatToGenCode(appId, prompt, loginUser);
        } catch (BusinessException e) {
            // 参数校验、鉴权、应用不存在等业务失败：直接以 error 数据帧结束，不抛出异常。
            // 不依赖客户端的 Accept 头，也不依赖 @RestControllerAdvice 的媒体类型协商。
            log.error("生成代码请求校验失败，appId: {}", appId, e);
            return Flux.just(buildErrorEvent(e.getMessage()), buildDoneEvent());
        } catch (RuntimeException e) {
            log.error("生成代码请求处理失败，appId: {}", appId, e);
            return Flux.just(buildErrorEvent("代码生成失败，请稍后重试"), buildDoneEvent());
        }

        return toSseFlux(chatToGenCodeFlux, appId);
    }

    /**
     * 续订当前应用的生成流（用于浏览器刷新/返回后重新接上正在进行的生成）
     * 方案 C 的配套接口：生成任务在服务端独立运行，客户端断开不影响它。
     * 客户端可用 fromSeq 或 subId 续订，服务端精确补发缺失帧。
     */
    @GetMapping(value = "/chat/gen/resume", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> resumeGenCode(@RequestParam Long appId, @RequestParam(required = false) Long fromSeq, @RequestParam(required = false) String subId, HttpServletRequest request) {
        Flux<GenerationTaskRegistry.SequencedFrame> flux;
        try {
            ThrowUtils.throwIfInvalidId(appId, "应用ID");
            User loginUser = userService.getLoginUser(request);
            flux = appService.resumeGenCode(appId, fromSeq == null ? -1L : fromSeq, StrUtil.nullToEmpty(subId), loginUser);
        } catch (BusinessException e) {
            log.error("续订生成流失败，appId: {}", appId, e);
            return Flux.just(buildErrorEvent(e.getMessage()), buildDoneEvent());
        } catch (RuntimeException e) {
            log.error("续订生成流异常，appId: {}", appId, e);
            return Flux.just(buildErrorEvent("续订生成流失败，请稍后重试"), buildDoneEvent());
        }
        return toSseFlux(flux, appId);
    }

    /** 查询当前生成任务状态（前端轮询/判断是否还能续订） */
    @GetMapping("/chat/gen/status")
    public BaseResponse<GenerationStatusVO> getGenStatus(@RequestParam Long appId, HttpServletRequest request) {
        ThrowUtils.throwIfInvalidId(appId, "应用ID");
        User loginUser = userService.getLoginUser(request);
        return ResultUtils.success(appService.getGenStatus(appId, loginUser));
    }

    /**
     * 把"带序号的文本数据流"包装成 SSE 响应：每条数据一个 data 帧（带 id 序号），最后补一个 done 事件
     * 注意：这里必须直接下发 chunk 字符串本身，交给前端统一解析。
     * 不要把 chunk 再包一层 JSON 字符串：ServerSentEvent 的 data 是 String 时……
     */
    private Flux<ServerSentEvent<String>> toSseFlux(Flux<GenerationTaskRegistry.SequencedFrame> flux, Long appId) {
        return flux
                .map(frame -> ServerSentEvent.<String>builder()
                        .id(String.valueOf(frame.seq()))
                        .data(frame.data())
                        .build())
                .concatWith(Mono.just(buildDoneEvent()))
                // 流内异常同样不再向上抛出：此时 SSE 响应已经提交，异常逃逸到 Servlet 层后
                // @RestControllerAdvice 会尝试以 JSON 回写错误（与 text/event-stream 冲突）并引发连锁异常。
                // 这里统一转成一个 type=error 的数据帧下发，前端可据此展示错误信息。
                .onErrorResume(error -> {
                    log.error("生成代码流式响应失败，appId: {}", appId, error);
                    return Flux.just(buildErrorEvent("代码生成失败，请稍后重试"), buildDoneEvent());
                });
    }

    /** 构建流式响应的结束事件（前端据此结束本次生成） */
    private static ServerSentEvent<String> buildDoneEvent() {
        // 发送一个特殊的事件，表示流结束
        return ServerSentEvent.<String>builder()
                .event("done")
                .data("")
                .build();
    }

    /**
     * 构建流式响应的错误数据帧
     * 与正常消息保持一致，仍然以 data 帧下发（type=error），不使用 SSE 具名的 error 事件，
     * 避免与 EventSource 自身的连接错误事件混淆，前端按 type 分发即可。
     */
    private static ServerSentEvent<String> buildErrorEvent(String errorMessage) {
        return ServerSentEvent.<String>builder()
                .data(JSONUtil.toJsonStr(new ErrorMessage(errorMessage)))
                .build();
    }

    /**
     * 部署应用（同步：一次请求内完成构建与发布，返回部署地址）
     * 保留该接口是为了兼容现有前端；构建耗时较长时（Vue 工程要跑 npm install + build）
     * 建议改用 {@link #submitDeployApp} + {@link #getDeployStatus} 的异步方式……
     */
    @PostMapping("/deploy")
    public BaseResponse<String> deployApp(@RequestBody AppDeployRequest deployRequest, HttpServletRequest request) {

        ThrowUtils.throwIf(deployRequest == null, ErrorCode.PARAMS_ERROR, "请求参数为空");
        Long appId = deployRequest.getAppId();
        ThrowUtils.throwIfInvalidId(appId, "应用ID");

        User loginUser = userService.getLoginUser(request);

        String deployUrl = appService.deployApp(appId, loginUser);
        return ResultUtils.success(deployUrl);
    }

    /**
     * 提交异步部署任务（立即返回，后台构建与发布）
     * 与同步接口的差异：HTTP 请求不再阻塞在 npm 构建上，提交后通过
     * {@link #getDeployStatus} 轮询进度；同一个应用同时只有一个部署任务（CAS 抢占）……
     */
    @PostMapping("/deploy/async")
    public BaseResponse<DeployStatusVO> submitDeployApp(@RequestBody AppDeployRequest deployRequest, HttpServletRequest request) {
        ThrowUtils.throwIf(deployRequest == null, ErrorCode.PARAMS_ERROR, "请求参数为空");
        Long appId = deployRequest.getAppId();
        ThrowUtils.throwIfInvalidId(appId, "应用ID");
        User loginUser = userService.getLoginUser(request);
        return ResultUtils.success(appService.submitDeploy(appId, loginUser));
    }

    /**
     * 查询部署状态（异步部署轮询接口）
     * status 取值：idle（未部署）/ deploying（部署中）/ ready（已完成，deployUrl 可用）/ failed（失败，errorMessage 为原因）。
     */
    @GetMapping("/deploy/status")
    public BaseResponse<DeployStatusVO> getDeployStatus(@RequestParam Long appId, HttpServletRequest request) {
        ThrowUtils.throwIfInvalidId(appId, "应用ID");
        User loginUser = userService.getLoginUser(request);
        return ResultUtils.success(appService.getDeployStatus(appId, loginUser));
    }

    /** 下载应用（将应用打包为zip文件下载到本地） */
    @GetMapping("/download/{appId}")
    public void downloadApp(@PathVariable Long appId, HttpServletRequest request, HttpServletResponse response) {

        ThrowUtils.throwIfInvalidId(appId, "应用ID");

        App app = appService.getById(appId);
        ThrowUtils.throwIf(app == null, ErrorCode.NOT_FOUND_ERROR, "应用不存在");

        User loginUser = userService.getLoginUser(request);

        ThrowUtils.throwIf(!app.getUserId().equals(loginUser.getId()), ErrorCode.NO_AUTH_ERROR, "无权限下载该应用");

        String codeGenType = app.getCodeGenType();
        String sourceDirPath = AppPathUtils.projectDirPath(codeGenType, appId);
        File sourceDir = new File(sourceDirPath);
        ThrowUtils.throwIf(!sourceDir.exists() || !sourceDir.isDirectory(), ErrorCode.NOT_FOUND_ERROR, "应用代码目录不存在");

        String downloadFilename = String.valueOf(appId);

        projectDownloadService.downloadProjectAsZip(sourceDirPath, downloadFilename, response);
    }
}
