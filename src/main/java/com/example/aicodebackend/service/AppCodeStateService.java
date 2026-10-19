package com.example.aicodebackend.service;

import com.example.aicodebackend.mapper.AppMapper;
import com.example.aicodebackend.model.entity.App;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 应用代码状态：记录"代码最后一次被生成/修改的时间"
 * 为什么需要它：用户改完自己的应用后，希望重新部署能看到新内容。
 * "代码有没有变"的判定就落在 {@code edit_time} 上……
 */
@Slf4j
@Service
public class AppCodeStateService {

    private final AppMapper appMapper;

    public AppCodeStateService(AppMapper appMapper) {
        this.appMapper = appMapper;
    }

    /** 标记应用代码发生了变化（生成/AI 修改结束时调用） */
    public void markCodeChanged(Long appId) {
        if (appId == null) {
            return;
        }
        try {
            App update = new App();
            update.setId(appId);
            update.setEditTime(LocalDateTime.now());
            boolean updated = appMapper.update(update) > 0;
            if (updated) {
                log.debug("已刷新应用代码修改时间，appId: {}", appId);
            }
        } catch (Exception e) {
            log.warn("刷新应用代码修改时间失败（不影响生成），appId: {}", appId, e);
        }
    }
}
