package com.cortex.lab.controller;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONWriter;
import com.cortex.dto.ApiResponse;
import com.cortex.lab.dto.*;
import com.cortex.lab.service.AuthService;
import com.cortex.lab.service.LearningReportService;
import com.cortex.lab.service.NotificationService;
import com.cortex.lab.service.ReportNarrativeService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {
    // 认证控制器：注册、登录、用户信息、学习报表、导出

    private final AuthService authService;
    private final LearningReportService learningReportService;
    private final NotificationService notificationService;
    private final ReportNarrativeService reportNarrativeService;

    @PostMapping("/register")
    public ApiResponse<AuthResponse> register(@RequestBody RegisterRequest request) {
        // 用户注册
        try {
            AuthResponse resp = authService.register(request);
            return ApiResponse.success("注册成功", resp);
        } catch (Exception e) {
            log.error("注册失败", e);
            return ApiResponse.error(e.getMessage());
        }
    }

    @PostMapping("/login")
    public ApiResponse<AuthResponse> login(@RequestBody LoginRequest request) {
        // 用户登录
        try {
            AuthResponse resp = authService.login(request);
            return ApiResponse.success("登录成功", resp);
        } catch (Exception e) {
            log.error("登录失败", e);
            return ApiResponse.error(e.getMessage());
        }
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout(@RequestHeader("Authorization") String token) {
        // 退出登录
        try {
            if (token != null && token.startsWith("Bearer ")) {
                authService.logout(token.substring(7));
            }
            return ApiResponse.success(null);
        } catch (Exception e) {
            return ApiResponse.error(e.getMessage());
        }
    }

    @GetMapping("/me")
    public ApiResponse<UserInfoDTO> me(HttpServletRequest request) {
        // 获取当前登录用户信息
        String userId = (String) request.getAttribute("userId");
        if (userId == null) {
            return ApiResponse.error("未登录");
        }
        try {
            return ApiResponse.success(authService.getUserInfo(userId));
        } catch (Exception e) {
            return ApiResponse.error(e.getMessage());
        }
    }

    @GetMapping("/userinfo")
    public ApiResponse<UserInfoDTO> getUserInfo(@RequestParam String userId) {
        // 获取指定用户信息
        try {
            return ApiResponse.success(authService.getUserInfo(userId));
        } catch (Exception e) {
            log.error("获取用户信息失败", e);
            return ApiResponse.error(e.getMessage());
        }
    }

    @PostMapping("/record-answer")
    public ApiResponse<Void> recordAnswer(@RequestBody Map<String, Object> body) {
        // 记录答题结果
        try {
            String userId = (String) body.get("userId");
            boolean correct = Boolean.TRUE.equals(body.get("correct"));
            learningReportService.recordQuestionAnswered(userId, correct);
            return ApiResponse.success(null);
        } catch (Exception e) {
            return ApiResponse.error(e.getMessage());
        }
    }

    @GetMapping("/report/ai-narrative")
    public ApiResponse<String> getAiNarrative(@RequestParam String userId, @RequestParam(defaultValue = "WEEKLY") String type) {
        // AI 生成叙事性学习报告
        try {
            String narrative = reportNarrativeService.generateNarrative(userId, type);
            return ApiResponse.success(narrative);
        } catch (Exception e) {
            log.error("生成AI报告失败", e);
            return ApiResponse.error(e.getMessage());
        }
    }

    @GetMapping("/export")
    public ResponseEntity<byte[]> exportData(@RequestParam String userId) {
        // 导出用户学习数据
        try {
            var userInfo = authService.getUserInfo(userId);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("exportTime", new Date().toString());
            data.put("user", Map.of(
                "username", userInfo.getUsername(),
                "skillLevel", userInfo.getSkillLevel(),
                "totalQuestionsAnswered", userInfo.getTotalQuestionsAnswered(),
                "totalCorrect", userInfo.getTotalCorrect(),
                "accuracy", userInfo.getAccuracy(),
                "studyStreak", userInfo.getStudyStreak()
            ));

            String jsonStr = JSON.toJSONString(data, JSONWriter.Feature.PrettyFormat);
            byte[] bytes = jsonStr.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
            headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
            headers.setContentDispositionFormData("attachment", "learning-data.json");
            return org.springframework.http.ResponseEntity.ok().headers(headers).body(bytes);
        } catch (Exception e) {
            log.error("导出数据失败", e);
            throw new RuntimeException("导出失败: " + e.getMessage());
        }
    }

    @GetMapping("/notification/config")
    public ApiResponse<com.cortex.lab.entity.UserNotificationConfig> getNotificationConfig(@RequestParam String userId) {
        // 获取通知配置
        try {
            return ApiResponse.success(notificationService.getConfig(userId));
        } catch (Exception e) {
            return ApiResponse.error(e.getMessage());
        }
    }

    @PutMapping("/notification/config")
    public ApiResponse<com.cortex.lab.entity.UserNotificationConfig> updateNotificationConfig(@RequestBody Map<String, Object> body) {
        // 更新通知配置
        try {
            String userId = (String) body.get("userId");
            if (userId == null) {
                return ApiResponse.error("userId不能为空");
            }
            var config = notificationService.updateConfig(userId, body);
            // 主动模式变化时通知 AI 服务
            if (body.containsKey("aiActiveMode")) {
                try {
                    String json = JSON.toJSONString(Map.of("user_id", userId, "enabled", body.get("aiActiveMode")));
                    HttpClient.newHttpClient().sendAsync(
                        HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:8000/api/ai/active-mode"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(json))
                            .timeout(java.time.Duration.ofSeconds(2))
                            .build(),
                        HttpResponse.BodyHandlers.ofString()
                    );
                } catch (Exception ignored) {}
            }
            return ApiResponse.success("更新成功", config);
        } catch (Exception e) {
            return ApiResponse.error(e.getMessage());
        }
    }
}
