package com.example.aicodebackend.service;

import com.example.aicodebackend.model.dto.app.AppQueryRequest;
import com.example.aicodebackend.model.entity.App;
import com.example.aicodebackend.model.entity.User;
import com.example.aicodebackend.model.vo.AppVO;
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

    String deployApp(Long appId, User loginUser);
}
