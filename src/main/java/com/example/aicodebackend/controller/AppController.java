package com.example.aicodebackend.controller;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.example.aicodebackend.annotation.AuthCheck;
import com.example.aicodebackend.common.BaseResponse;
import com.example.aicodebackend.common.DeleteRequest;
import com.example.aicodebackend.common.ResultUtils;
import com.example.aicodebackend.constant.AppConstant;
import com.example.aicodebackend.constant.UserConstant;
import com.example.aicodebackend.exception.ErrorCode;
import com.example.aicodebackend.exception.ThrowUtils;
import com.example.aicodebackend.model.dto.app.*;
import com.example.aicodebackend.model.entity.App;
import com.example.aicodebackend.model.entity.User;
import com.example.aicodebackend.model.enums.AIModelTypeEnum;
import com.example.aicodebackend.model.enums.CodeGenTypeEnum;
import com.example.aicodebackend.model.vo.AppVO;
import com.example.aicodebackend.service.AppService;
import com.example.aicodebackend.service.UserService;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/app")
public class AppController {

    @Resource
    private AppService appService;

    @Resource
    private UserService userService;

    /**
     * 用户创建应用（只需填写初始化 prompt，应用名称和代码生成类型由系统自动生成）
     */
    @PostMapping("/add")
    public BaseResponse<Long> addApp(@RequestBody AppAddRequest appAddRequest, HttpServletRequest request) {
        // 1. 校验参数
        ThrowUtils.throwIf(appAddRequest == null, ErrorCode.PARAMS_ERROR);
        String initPrompt = appAddRequest.getInitPrompt();
        ThrowUtils.throwIf(StrUtil.isBlank(initPrompt), ErrorCode.PARAMS_ERROR, "初始化 prompt 不能为空");
        // 2. 获取当前登录用户
        User loginUser = userService.getLoginUser(request);
        // 3. 校验代码生成类型（用户可不传，默认使用原生多文件模式）
        CodeGenTypeEnum codeGenTypeEnum = CodeGenTypeEnum.getEnumByValue(appAddRequest.getCodeGenType());
        ThrowUtils.throwIf(codeGenTypeEnum == null, ErrorCode.PARAMS_ERROR, "不支持的代码生成类型");
        // 4. 校验 AI 模型类型（用户可不传，默认使用 deepseek-flash）
        AIModelTypeEnum aiModelTypeEnum = AIModelTypeEnum.getEnumByValue(appAddRequest.getAiModelType());
        ThrowUtils.throwIf(aiModelTypeEnum == null, ErrorCode.PARAMS_ERROR, "不支持的 AI 模型类型");
        // 5. 复制属性，并补全默认值
        App app = new App();
        BeanUtil.copyProperties(appAddRequest, app);
        app.setUserId(loginUser.getId());
        // 应用名称由系统自动生成：取初始化提示词的前 12 位（压缩空白字符，避免名称中出现换行）
        app.setAppName(generateAppName(initPrompt));
        // 代码生成类型与 AI 模型类型由用户指定（未指定时已在请求体中回落到默认值）
        app.setCodeGenType(codeGenTypeEnum.getValue());
        app.setAiModelType(aiModelTypeEnum.getValue());
        // 6. 调用服务创建应用
        boolean result = appService.save(app);
        ThrowUtils.throwIf(!result, ErrorCode.SYSTEM_ERROR, "创建应用失败");
        return ResultUtils.success(app.getId());
    }

    /**
     * 根据初始化提示词自动生成应用名称（取前 12 位）
     *
     * @param initPrompt 初始化提示词
     *
     * @return 应用名称
     */
    private String generateAppName(String initPrompt) {
        String trimmedPrompt = initPrompt.replaceAll("\\s+", " ").trim();
        return StrUtil.sub(trimmedPrompt, 0, AppConstant.APP_NAME_MAX_LENGTH);
    }

    /**
     * 用户修改自己的应用（目前只支持修改应用名称）
     */
    @PostMapping("/update")
    public BaseResponse<Boolean> updateApp(@RequestBody AppUpdateRequest appUpdateRequest, HttpServletRequest request) {
        // 1. 校验参数
        ThrowUtils.throwIf(appUpdateRequest == null, ErrorCode.PARAMS_ERROR);
        Long id = appUpdateRequest.getId();
        ThrowUtils.throwIf(id == null || id <= 0, ErrorCode.PARAMS_ERROR, "应用ID不合法");
        ThrowUtils.throwIf(StrUtil.isBlank(appUpdateRequest.getAppName()), ErrorCode.PARAMS_ERROR, "应用名称不能为空");
        // 2. 获取当前登录用户
        User loginUser = userService.getLoginUser(request);
        // 3. 校验应用是否存在
        App oldApp = appService.getById(id);
        ThrowUtils.throwIf(oldApp == null, ErrorCode.NOT_FOUND_ERROR, "应用不存在");
        // 4. 校验应用归属，只能修改自己的应用
        ThrowUtils.throwIf(!oldApp.getUserId().equals(loginUser.getId()), ErrorCode.NO_AUTH_ERROR, "无权限修改该应用");
        // 5. 调用服务更新应用
        App app = new App();
        app.setId(id);
        app.setAppName(appUpdateRequest.getAppName());
        app.setEditTime(LocalDateTime.now());
        boolean result = appService.updateById(app);
        ThrowUtils.throwIf(!result, ErrorCode.SYSTEM_ERROR, "更新应用失败");
        return ResultUtils.success(true);
    }

    /**
     * 用户删除自己的应用
     */
    @PostMapping("/delete")
    public BaseResponse<Boolean> deleteApp(@RequestBody DeleteRequest deleteRequest, HttpServletRequest request) {
        // 1. 校验参数
        ThrowUtils.throwIf(deleteRequest == null || deleteRequest.getId() == null, ErrorCode.PARAMS_ERROR);
        Long id = deleteRequest.getId();
        // 2. 获取当前登录用户
        User loginUser = userService.getLoginUser(request);
        // 3. 校验应用是否存在
        App oldApp = appService.getById(id);
        ThrowUtils.throwIf(oldApp == null, ErrorCode.NOT_FOUND_ERROR, "应用不存在");
        // 4. 校验应用归属，只有本人或者管理员才能删除的应用
        ThrowUtils.throwIf(!oldApp.getUserId().equals(loginUser.getId()) && !UserConstant.ADMIN_ROLE.equals(loginUser.getUserRole()), ErrorCode.NO_AUTH_ERROR, "无权限删除该应用");
        // 5. 调用服务删除应用（逻辑删除，同时关联删除该应用的对话历史）
        appService.deleteApp(id);
        return ResultUtils.success(true);
    }

    /**
     * 根据ID获取应用详情VO
     */
    @GetMapping("/get/vo")
    public BaseResponse<AppVO> getAppVOById(@RequestParam Long id) {
        // 1. 校验参数
        ThrowUtils.throwIf(id == null || id <= 0, ErrorCode.PARAMS_ERROR, "应用ID不合法");
        // 2. 调用服务获取应用信息
        App app = appService.getById(id);
        ThrowUtils.throwIf(app == null, ErrorCode.NOT_FOUND_ERROR, "应用不存在");
        // 3. 转换为应用信息VO
        AppVO appVO = appService.getAppVO(app);
        return ResultUtils.success(appVO);
    }

    /**
     * 分页获取当前用户自己的应用列表（支持根据名称查询，每页最多 20 个）
     */
    @PostMapping("/my/list/page/vo")
    public BaseResponse<Page<AppVO>> listMyAppVOByPage(@RequestBody AppQueryRequest appQueryRequest, HttpServletRequest request) {
        // 1. 校验参数
        ThrowUtils.throwIf(appQueryRequest == null, ErrorCode.PARAMS_ERROR, "查询参数为空");
        // 2. 取出分页参数并限制每页最多 20 个
        long pageNum = appQueryRequest.getPageNum();
        long pageSize = appQueryRequest.getPageSize();
        ThrowUtils.throwIf(pageNum <= 0, ErrorCode.PARAMS_ERROR, "页码不合法");
        ThrowUtils.throwIf(pageSize <= 0 || pageSize > 20, ErrorCode.PARAMS_ERROR, "每页最多查询 20 个应用");
        // 3. 获取当前登录用户，只查询自己的应用
        User loginUser = userService.getLoginUser(request);
        appQueryRequest.setUserId(loginUser.getId());
        // 4. 调用服务获取分页应用信息
        QueryWrapper queryWrapper = appService.getQueryWrapper(appQueryRequest);
        Page<App> appPage = appService.page(Page.of(pageNum, pageSize), queryWrapper);
        // 5. 转换为分页响应对象
        Page<AppVO> appVOPage = new Page<>(pageNum, pageSize, appPage.getTotalRow());
        List<AppVO> appVOList = appService.getAppVOList(appPage.getRecords());
        appVOPage.setRecords(appVOList);
        return ResultUtils.success(appVOPage);
    }

    /**
     * 分页获取精选应用列表（支持根据名称查询，每页最多 20 个）
     */
    @PostMapping("/good/list/page/vo")
    public BaseResponse<Page<AppVO>> listGoodAppVOByPage(@RequestBody AppQueryRequest appQueryRequest) {
        // 1. 校验参数
        ThrowUtils.throwIf(appQueryRequest == null, ErrorCode.PARAMS_ERROR, "查询参数为空");
        // 2. 取出分页参数并限制每页最多 20 个
        long pageNum = appQueryRequest.getPageNum();
        long pageSize = appQueryRequest.getPageSize();
        ThrowUtils.throwIf(pageNum <= 0, ErrorCode.PARAMS_ERROR, "页码不合法");
        ThrowUtils.throwIf(pageSize <= 0 || pageSize > 20, ErrorCode.PARAMS_ERROR, "每页最多查询 20 个应用");
        // 3. 只查询精选的应用
        appQueryRequest.setPriority(AppConstant.GOOD_APP_PRIORITY);
        // 4. 调用服务获取分页应用信息
        QueryWrapper queryWrapper = appService.getQueryWrapper(appQueryRequest);
        Page<App> appPage = appService.page(Page.of(pageNum, pageSize), queryWrapper);
        // 5. 转换为分页响应对象
        Page<AppVO> appVOPage = new Page<>(pageNum, pageSize, appPage.getTotalRow());
        List<AppVO> appVOList = appService.getAppVOList(appPage.getRecords());
        appVOPage.setRecords(appVOList);
        return ResultUtils.success(appVOPage);
    }

    /**
     * 根据ID删除任意应用（仅管理员可用）
     */
    @PostMapping("/admin/delete")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Boolean> deleteAppByAdmin(@RequestBody DeleteRequest deleteRequest) {
        // 1. 校验参数
        ThrowUtils.throwIf(deleteRequest == null || deleteRequest.getId() == null, ErrorCode.PARAMS_ERROR);
        Long id = deleteRequest.getId();
        // 2. 校验应用是否存在
        App oldApp = appService.getById(id);
        ThrowUtils.throwIf(oldApp == null, ErrorCode.NOT_FOUND_ERROR, "应用不存在");
        // 3. 调用服务删除应用（逻辑删除，同时关联删除该应用的对话历史）
        appService.deleteApp(id);
        return ResultUtils.success(true);
    }

    /**
     * 根据ID更新任意应用（仅管理员可用，支持更新应用名称、应用封面、优先级）
     */
    @PostMapping("/admin/update")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Boolean> updateAppByAdmin(@RequestBody AppAdminUpdateRequest appAdminUpdateRequest) {
        // 1. 校验参数
        ThrowUtils.throwIf(appAdminUpdateRequest == null, ErrorCode.PARAMS_ERROR);
        Long id = appAdminUpdateRequest.getId();
        ThrowUtils.throwIf(id == null || id <= 0, ErrorCode.PARAMS_ERROR, "应用ID不合法");
        // 2. 校验应用是否存在
        App oldApp = appService.getById(id);
        ThrowUtils.throwIf(oldApp == null, ErrorCode.NOT_FOUND_ERROR, "应用不存在");
        // 3. 复制属性并更新应用
        App app = new App();
        BeanUtil.copyProperties(appAdminUpdateRequest, app);
        app.setEditTime(LocalDateTime.now());
        boolean result = appService.updateById(app);
        ThrowUtils.throwIf(!result, ErrorCode.SYSTEM_ERROR, "更新应用失败");
        return ResultUtils.success(true);
    }

    /**
     * 分页获取应用信息列表（仅管理员可用，支持根据除时间外的任何字段查询，每页数量不限）
     */
    @PostMapping("/admin/list/page/vo")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Page<AppVO>> listAppVOByPageByAdmin(@RequestBody AppQueryRequest appQueryRequest) {
        // 1. 校验参数
        ThrowUtils.throwIf(appQueryRequest == null, ErrorCode.PARAMS_ERROR, "查询参数为空");
        // 2. 取出分页参数
        long pageNum = appQueryRequest.getPageNum();
        long pageSize = appQueryRequest.getPageSize();
        ThrowUtils.throwIf(pageNum <= 0, ErrorCode.PARAMS_ERROR, "页码不合法");
        ThrowUtils.throwIf(pageSize <= 0, ErrorCode.PARAMS_ERROR, "每页条数不合法");
        // 3. 调用服务获取分页应用信息
        QueryWrapper queryWrapper = appService.getQueryWrapper(appQueryRequest);
        Page<App> appPage = appService.page(Page.of(pageNum, pageSize), queryWrapper);
        // 4. 转换为分页响应对象
        Page<AppVO> appVOPage = new Page<>(pageNum, pageSize, appPage.getTotalRow());
        List<AppVO> appVOList = appService.getAppVOList(appPage.getRecords());
        appVOPage.setRecords(appVOList);
        return ResultUtils.success(appVOPage);
    }

    /**
     * 根据ID获取任意应用详情VO（仅管理员可用）
     */
    @GetMapping("/admin/get/vo")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<AppVO> getAppVOByIdByAdmin(@RequestParam Long id) {
        // 1. 校验参数
        ThrowUtils.throwIf(id == null || id <= 0, ErrorCode.PARAMS_ERROR, "应用ID不合法");
        // 2. 调用服务获取应用信息
        App app = appService.getById(id);
        ThrowUtils.throwIf(app == null, ErrorCode.NOT_FOUND_ERROR, "应用不存在");
        // 3. 转换为应用信息VO
        AppVO appVO = appService.getAppVO(app);
        return ResultUtils.success(appVO);
    }

    /**
     * 根据应用ID和用户输入的 prompt，调用服务生成代码，并通过 SSE 流式返回生成的代码块
     *
     * @param appId   应用ID
     * @param prompt  用户输入的 prompt
     * @param request HTTP 请求对象
     *
     * @return SSE 流式返回生成的代码块
     */
    @GetMapping(value = "/chat/gen/code", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> chatToGenCode(@RequestParam Long appId, @RequestParam String prompt, HttpServletRequest request) {
        // 1. 校验参数
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR, "应用ID不合法");
        ThrowUtils.throwIf(StrUtil.isBlank(prompt), ErrorCode.PARAMS_ERROR, "prompt不能为空");
        // 2. 获取当前登录用户
        User loginUser = userService.getLoginUser(request);
        // 3. 调用服务生成代码
        Flux<String> chatToGenCodeFlux = appService.chatToGenCode(appId, prompt, loginUser);

        return chatToGenCodeFlux
                .map(chunk -> ServerSentEvent.<String>builder()
                        // 注意：这里必须直接下发 chunk 字符串本身，交给前端统一解析。
                        // 不要把 chunk 再包一层 JSON 字符串：ServerSentEvent 的 data 是 String 时，
                        // Spring 会按 JSON 序列化该字符串，导致内容被二次转义（{"d":"{\"type\":...}"}），
                        // 前端的 JSON.parse 只能拿到被转义的字符串，无法按 type 分发消息。
                        .data(chunk)
                        .build())
                .concatWith(Mono.just(
                        // 发送一个特殊的事件，表示流结束
                        ServerSentEvent.<String>builder()
                                .event("done")
                                .data("")
                                .build()
                ));
    }

    /**
     * 部署应用（将应用部署到服务器上，返回部署结果）
     */
    @PostMapping("/deploy")
    public BaseResponse<String> deployApp(@RequestBody AppDeployRequest deployRequest, HttpServletRequest request) {
        // 1. 校验参数
        ThrowUtils.throwIf(deployRequest == null, ErrorCode.PARAMS_ERROR, "请求参数为空");
        Long appId = deployRequest.getAppId();
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR, "应用ID不合法");
        // 2. 获取当前登录用户
        User loginUser = userService.getLoginUser(request);
        // 3. 调用服务部署应用
        String deployUrl = appService.deployApp(appId, loginUser);
        return ResultUtils.success(deployUrl);
    }
}
