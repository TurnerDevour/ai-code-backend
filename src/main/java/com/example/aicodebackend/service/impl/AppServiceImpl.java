package com.example.aicodebackend.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import com.example.aicodebackend.constant.AppConstant;
import com.example.aicodebackend.core.AiCodeGeneratorFacade;
import com.example.aicodebackend.exception.BusinessException;
import com.example.aicodebackend.exception.ErrorCode;
import com.example.aicodebackend.exception.ThrowUtils;
import com.example.aicodebackend.mapper.AppMapper;
import com.example.aicodebackend.model.dto.app.AppQueryRequest;
import com.example.aicodebackend.model.entity.App;
import com.example.aicodebackend.model.entity.User;
import com.example.aicodebackend.model.enums.CodeGenTypeEnum;
import com.example.aicodebackend.model.vo.AppVO;
import com.example.aicodebackend.model.vo.UserVO;
import com.example.aicodebackend.service.AppService;
import com.example.aicodebackend.service.UserService;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.spring.service.impl.ServiceImpl;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.io.File;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class AppServiceImpl extends ServiceImpl<AppMapper, App> implements AppService {

    @Resource
    private UserService userService;

    @Resource
    private AiCodeGeneratorFacade aiCodeGeneratorFacade;

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
     * @param appId       应用ID
     * @param prompt      用户提供的提示，用于指导代码生成。
     * @param loginUser   当前登录用户
     * @return 生成的代码流
     */
    @Override
    public Flux<String> chatToGenCode(Long appId, String prompt, User loginUser) {
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

        // 5. 调用 AiCodeGeneratorFacade 生成代码并返回流式输出
        return aiCodeGeneratorFacade.generateAndSaveCodeStream(prompt, codeGenTypeEnum, appId);
    }

    /**
     * 部署应用
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

        // 2. 获取应用信息
        App app = this.getById(appId);
        ThrowUtils.throwIf(app == null, ErrorCode.NOT_FOUND_ERROR, "应用不存在");

        // 3. 验证用户是否有权限访问该应用，仅允许应用的创建者访问
        ThrowUtils.throwIf(!app.getUserId().equals(loginUser.getId()), ErrorCode.NO_AUTH_ERROR, "无权限访问该应用");

        // 4. 检查是否已有deployKey，如果没有则生成一个新的deployKey，否则使用已有的deployKey
        String deployKey = app.getDeployKey();
        if (StrUtil.isBlank(deployKey)) {
            deployKey = RandomUtil.randomString(6);
        }
        // 5. 获取代码生成类型，生成部署地址
        String codeGenType = app.getCodeGenType();
        String sourceDirName = codeGenType + "_" + appId;
        String sourceDirPath = AppConstant.CODE_OUTPUT_ROOT_DIR + File.separator + sourceDirName;
        // 6. 检查源目录是否存在，如果不存在则抛出异常
        File sourceDir = new File(sourceDirPath);
        if (!sourceDir.exists() || !sourceDir.isDirectory()) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "源代码目录不存在，请先生成代码");
        }
        // 7.复制文件到部署目录
        String deployDirPath = AppConstant.CODE_DEPLOY_ROOT_DIR + File.separator + deployKey;
        try {
            FileUtil.copyContent(sourceDir, new File(deployDirPath), true);
        } catch (Exception e) {
            log.error("部署应用失败", e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "部署应用失败：" + e.getMessage());
        }
        // 8. 更新应用的deployKey和部署时间
        App updateApp = new App();
        updateApp.setId(appId);
        updateApp.setDeployKey(deployKey);
        updateApp.setDeployedTime(LocalDateTime.now());
        boolean updateResult = this.updateById(updateApp);
        ThrowUtils.throwIf(!updateResult, ErrorCode.SYSTEM_ERROR, "更新应用部署信息失败");
        // 9. 返回部署地址
        return String.format("%s/%s/", AppConstant.CODE_DEPLOY_HOST, deployKey);
    }
}
