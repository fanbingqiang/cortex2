package com.cortex.lab.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cortex.lab.entity.UserNotificationConfig;
import com.cortex.lab.mapper.UserNotificationConfigMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {
    // 通知配置服务

    private final UserNotificationConfigMapper configMapper;

    // 获取用户通知配置，不存在则创建默认
    public UserNotificationConfig getConfig(String userId) {
        UserNotificationConfig config = configMapper.selectOne(
            new LambdaQueryWrapper<UserNotificationConfig>()
                .eq(UserNotificationConfig::getUserId, userId)
        );
        if (config == null) {
            config = new UserNotificationConfig();
            config.setUserId(userId);
            config.setEmailNotifications(false);
            config.setPushNotifications(false);
            config.setReviewReminder(true);
            config.setReportMonthly(true);
            config.setAiActiveMode(true);
            config.setGmtCreate(LocalDateTime.now());
            config.setGmtModified(LocalDateTime.now());
            configMapper.insert(config);
        }
        return config;
    }

    // 更新用户通知配置
    public UserNotificationConfig updateConfig(String userId, Map<String, Object> updates) {
        UserNotificationConfig config = getConfig(userId);

        if (updates.containsKey("emailNotifications")) {
            config.setEmailNotifications(Boolean.TRUE.equals(updates.get("emailNotifications")));
        }
        if (updates.containsKey("emailAddress")) {
            config.setEmailAddress((String) updates.get("emailAddress"));
        }
        if (updates.containsKey("pushNotifications")) {
            config.setPushNotifications(Boolean.TRUE.equals(updates.get("pushNotifications")));
        }
        if (updates.containsKey("reviewReminder")) {
            config.setReviewReminder(Boolean.TRUE.equals(updates.get("reviewReminder")));
        }
        if (updates.containsKey("reportWeekly")) {
            config.setReportWeekly(Boolean.TRUE.equals(updates.get("reportWeekly")));
        }
        if (updates.containsKey("reportMonthly")) {
            config.setReportMonthly(Boolean.TRUE.equals(updates.get("reportMonthly")));
        }
        if (updates.containsKey("aiActiveMode")) {
            config.setAiActiveMode(Boolean.TRUE.equals(updates.get("aiActiveMode")));
        }

        config.setGmtModified(LocalDateTime.now());
        configMapper.updateById(config);
        return config;
    }
}
