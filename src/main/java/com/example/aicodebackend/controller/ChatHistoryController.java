package com.example.aicodebackend.controller;

import com.example.aicodebackend.annotation.AuthCheck;
import com.example.aicodebackend.common.BaseResponse;
import com.example.aicodebackend.common.ResultUtils;
import com.example.aicodebackend.constant.ChatHistoryConstant;
import com.example.aicodebackend.constant.UserConstant;
import com.example.aicodebackend.exception.ErrorCode;
import com.example.aicodebackend.exception.ThrowUtils;
import com.example.aicodebackend.model.dto.chathistory.ChatHistoryQueryRequest;
import com.example.aicodebackend.model.entity.App;
import com.example.aicodebackend.model.entity.ChatHistory;
import com.example.aicodebackend.model.entity.User;
import com.example.aicodebackend.service.AppService;
import com.example.aicodebackend.service.ChatHistoryService;
import com.example.aicodebackend.service.UserService;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/chatHistory")
public class ChatHistoryController {

    @Resource
    private ChatHistoryService chatHistoryService;

    @Resource
    private AppService appService;

    @Resource
    private UserService userService;

    /**
     * 游标分页查询某个应用的对话历史（仅应用创建者和管理员可见）
     * <p>
     * 不传 lastCreateTime 时查询最新的 N 条消息；传入上一次查询结果中最早一条消息的 createTime，即可向前加载更多历史记录。
     *
     * @param appId                   应用id
     * @param chatHistoryQueryRequest 查询请求（可不传，默认每次加载最新 10 条）
     * @param request                 HTTP 请求对象
     *
     * @return 对话历史分页对象（按创建时间降序，最新的消息在前）
     */
    @PostMapping("/app/{appId}")
    public BaseResponse<Page<ChatHistory>> listAppChatHistory(@PathVariable Long appId, @RequestBody(required = false) ChatHistoryQueryRequest chatHistoryQueryRequest, HttpServletRequest request) {
        // 1. 校验参数
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR, "应用ID不合法");
        // 不传请求体时，默认加载最新 10 条消息
        if (chatHistoryQueryRequest == null) {
            chatHistoryQueryRequest = new ChatHistoryQueryRequest();
        }
        long pageSize = chatHistoryQueryRequest.getPageSize();
        ThrowUtils.throwIf(pageSize <= 0 || pageSize > ChatHistoryConstant.MAX_PAGE_SIZE, ErrorCode.PARAMS_ERROR, "每次最多加载 " + ChatHistoryConstant.MAX_PAGE_SIZE + " 条消息");
        // 2. 校验应用是否存在
        App app = appService.getById(appId);
        ThrowUtils.throwIf(app == null, ErrorCode.NOT_FOUND_ERROR, "应用不存在");
        // 3. 校验权限：仅应用创建者和管理员可见
        User loginUser = userService.getLoginUser(request);
        boolean isAdmin = UserConstant.ADMIN_ROLE.equals(loginUser.getUserRole());
        ThrowUtils.throwIf(!isAdmin && !app.getUserId().equals(loginUser.getId()), ErrorCode.NO_AUTH_ERROR, "无权限查看该应用的对话历史");
        // 4. 调用服务游标分页查询对话历史
        Page<ChatHistory> chatHistoryPage = chatHistoryService.listAppChatHistoryByPage(appId, pageSize, chatHistoryQueryRequest.getLastCreateTime(), chatHistoryQueryRequest);
        return ResultUtils.success(chatHistoryPage);
    }

    /**
     * 分页查询所有应用的对话历史（仅管理员可用，按创建时间降序，便于内容监管）
     *
     * @param chatHistoryQueryRequest 查询请求
     *
     * @return 对话历史分页对象
     */
    @PostMapping("/admin/list/page")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Page<ChatHistory>> listAllChatHistoryByPageForAdmin(@RequestBody ChatHistoryQueryRequest chatHistoryQueryRequest) {
        // 1. 校验参数
        ThrowUtils.throwIf(chatHistoryQueryRequest == null, ErrorCode.PARAMS_ERROR, "查询参数为空");
        // 2. 取出分页参数
        long pageNum = chatHistoryQueryRequest.getPageNum();
        long pageSize = chatHistoryQueryRequest.getPageSize();
        ThrowUtils.throwIf(pageNum <= 0, ErrorCode.PARAMS_ERROR, "页码不合法");
        ThrowUtils.throwIf(pageSize <= 0, ErrorCode.PARAMS_ERROR, "每页条数不合法");
        // 3. 强制按创建时间降序，便于内容监管
        chatHistoryQueryRequest.setSortField("create_time");
        chatHistoryQueryRequest.setSortOrder("descend");
        QueryWrapper queryWrapper = chatHistoryService.getQueryWrapper(chatHistoryQueryRequest);
        Page<ChatHistory> chatHistoryPage = chatHistoryService.page(Page.of(pageNum, pageSize), queryWrapper);
        return ResultUtils.success(chatHistoryPage);
    }
}
