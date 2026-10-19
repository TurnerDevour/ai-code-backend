package com.example.aicodebackend.controller;

import com.example.aicodebackend.annotation.AuthCheck;
import com.example.aicodebackend.common.BaseResponse;
import com.example.aicodebackend.common.PageRequest;
import com.example.aicodebackend.common.PageUtils;
import com.example.aicodebackend.common.ResultUtils;
import com.example.aicodebackend.constant.ChatHistoryConstant;
import com.example.aicodebackend.exception.ErrorCode;
import com.example.aicodebackend.exception.ThrowUtils;
import com.example.aicodebackend.model.dto.chathistory.ChatHistoryQueryRequest;
import com.example.aicodebackend.model.entity.App;
import com.example.aicodebackend.model.entity.ChatHistory;
import com.example.aicodebackend.model.entity.User;
import com.example.aicodebackend.model.enums.UserRoleEnum;
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
     * 不传 lastCreateTime 时查询最新的 N 条消息；传入上一次查询结果中最早一条消息的 createTime，即可向前加载更多历史记录。
     */
    @PostMapping("/app/{appId}")
    public BaseResponse<Page<ChatHistory>> listAppChatHistory(@PathVariable Long appId, @RequestBody(required = false) ChatHistoryQueryRequest chatHistoryQueryRequest, HttpServletRequest request) {

        ThrowUtils.throwIfInvalidId(appId, "应用ID");
        // 不传请求体时，默认加载最新 10 条消息
        if (chatHistoryQueryRequest == null) {
            chatHistoryQueryRequest = new ChatHistoryQueryRequest();
        }
        long pageSize = chatHistoryQueryRequest.getPageSize();
        ThrowUtils.throwIf(pageSize <= 0 || pageSize > ChatHistoryConstant.MAX_PAGE_SIZE, ErrorCode.PARAMS_ERROR, "每次最多加载 " + ChatHistoryConstant.MAX_PAGE_SIZE + " 条消息");

        App app = appService.getById(appId);
        ThrowUtils.throwIf(app == null, ErrorCode.NOT_FOUND_ERROR, "应用不存在");

        User loginUser = userService.getLoginUser(request);
        boolean isAdmin = UserRoleEnum.isAdmin(loginUser.getUserRole());
        ThrowUtils.throwIf(!isAdmin && !app.getUserId().equals(loginUser.getId()), ErrorCode.NO_AUTH_ERROR, "无权限查看该应用的对话历史");

        Page<ChatHistory> chatHistoryPage = chatHistoryService.listAppChatHistoryByPage(appId, pageSize, chatHistoryQueryRequest.getLastCreateTime(), chatHistoryQueryRequest);
        return ResultUtils.success(chatHistoryPage);
    }

    /** 分页查询所有应用的对话历史（仅管理员可用，按创建时间降序，便于内容监管） */
    @PostMapping("/admin/list/page")
    @AuthCheck(mustRole = UserRoleEnum.ADMIN_VALUE)
    public BaseResponse<Page<ChatHistory>> listAllChatHistoryByPageForAdmin(@RequestBody ChatHistoryQueryRequest chatHistoryQueryRequest) {

        ThrowUtils.throwIf(chatHistoryQueryRequest == null, ErrorCode.PARAMS_ERROR, "查询参数为空");

        long pageNum = chatHistoryQueryRequest.getPageNum();
        long pageSize = chatHistoryQueryRequest.getPageSize();
        PageUtils.validate(pageNum, pageSize);
        chatHistoryQueryRequest.setSortField(PageRequest.DEFAULT_SORT_FIELD);
        chatHistoryQueryRequest.setSortOrder(PageRequest.DESCEND);
        QueryWrapper queryWrapper = chatHistoryService.getQueryWrapper(chatHistoryQueryRequest);
        Page<ChatHistory> chatHistoryPage = chatHistoryService.page(Page.of(pageNum, pageSize), queryWrapper);
        return ResultUtils.success(chatHistoryPage);
    }
}
