package com.example.aicodebackend.service;

import com.example.aicodebackend.mapper.AppMapper;
import com.example.aicodebackend.model.entity.App;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 应用代码状态：记录"代码最后一次被生成/修改的时间"
 * <p>
 * 为什么需要它：用户改完自己的应用后，希望重新部署能看到新内容。
 * "代码有没有变"的判定就落在 {@code edit_time} 上：
 * <ul>
 *     <li>生成/AI 修改代码结束时刷新 {@code edit_time}；</li>
 *     <li>部署成功时写入 {@code deployed_time}；</li>
 *     <li>于是 {@code edit_time > deployed_time} 就表示"线上还是旧内容，可以重新部署"。</li>
 * </ul>
 * 注意不能用 {@code update_time}：部署本身也会把它刷新，会立刻把"已部署"误判成"需要重新部署"。
 * <p>
 * 失败只记日志、不抛异常：这只影响"是否提示重新部署"，不应该影响代码生成主流程。
 */
@Slf4j
@Service
public class AppCodeStateService {

    private final AppMapper appMapper;

    public AppCodeStateService(AppMapper appMapper) {
        this.appMapper = appMapper;
    }

    /**
     * 标记应用代码发生了变化（生成/AI 修改结束时调用）
     *
     * @param appId 应用ID
     */
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
