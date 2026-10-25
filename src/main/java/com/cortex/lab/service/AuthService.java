package com.cortex.lab.service;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cortex.auth.JwtService;
import com.cortex.lab.dto.*;
import com.cortex.lab.entity.*;
import com.cortex.lab.mapper.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserMapper userMapper;
    private final QuestionProgressMapper questionProgressMapper;
    private final KnowledgeCardMapper knowledgeCardMapper;
    private final JwtService jwtService;

    private final Map<String, User> tokenStore = new java.util.concurrent.ConcurrentHashMap<>();

    // 用户注册
    public AuthResponse register(RegisterRequest request) {
        if (StrUtil.isBlank(request.getUsername()) || StrUtil.isBlank(request.getPassword())) {
            throw new RuntimeException("用户名和密码不能为空");
        }

        Long count = userMapper.selectCount(
            new LambdaQueryWrapper<User>().eq(User::getUsername, request.getUsername())
        );
        if (count > 0) {
            throw new RuntimeException("用户名已存在");
        }

        String userId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        User user = new User();
        user.setUserId(userId);
        user.setUsername(request.getUsername());
        user.setPasswordHash(DigestUtil.sha256Hex(request.getPassword()));
        user.setEmail(request.getEmail());
        user.setRole("USER");
        user.setStatus("ACTIVE");
        user.setTotalStudyHours(0.0);
        user.setTotalQuestionsAnswered(0);
        user.setTotalCorrect(0);
        user.setStudyStreak(0);
        user.setSkillLevel("BEGINNER");
        user.setGmtCreate(LocalDateTime.now());
        user.setGmtModified(LocalDateTime.now());
        userMapper.insert(user);

        String token = jwtService.generateToken(userId, user.getRole(), "default-org");
        tokenStore.put(token, user);

        AuthResponse resp = new AuthResponse();
        resp.setToken(token);
        resp.setUserId(userId);
        resp.setUsername(user.getUsername());
        resp.setEmail(user.getEmail());
        resp.setRole(user.getRole());
        return resp;
    }

    // 用户登录
    public AuthResponse login(LoginRequest request) {
        if (StrUtil.isBlank(request.getUsername()) || StrUtil.isBlank(request.getPassword())) {
            throw new RuntimeException("用户名和密码不能为空");
        }

        User user = userMapper.selectOne(
            new LambdaQueryWrapper<User>().eq(User::getUsername, request.getUsername())
        );
        if (user == null) {
            throw new RuntimeException("用户不存在");
        }

        String hashed = DigestUtil.sha256Hex(request.getPassword());
        if (!hashed.equals(user.getPasswordHash())) {
            throw new RuntimeException("密码错误");
        }

        if ("BANNED".equals(user.getStatus())) {
            throw new RuntimeException("账号已被封禁，请联系管理员");
        }

        user.setLastLoginTime(LocalDateTime.now());
        user.setGmtModified(LocalDateTime.now());
        userMapper.updateById(user);

        String token = jwtService.generateToken(user.getUserId(), user.getRole(), "default-org");
        tokenStore.put(token, user);

        AuthResponse resp = new AuthResponse();
        resp.setToken(token);
        resp.setUserId(user.getUserId());
        resp.setUsername(user.getUsername());
        resp.setEmail(user.getEmail());
        resp.setAvatar(user.getAvatar());
        resp.setRole(user.getRole());
        return resp;
    }

    // 验证 token 有效性
    public User validateToken(String token) {
        if (token == null) return null;
        return tokenStore.get(token);
    }

    // 获取用户信息（含学习画像）
    public UserInfoDTO getUserInfo(String userId) {
        User user = userMapper.selectOne(
            new LambdaQueryWrapper<User>().eq(User::getUserId, userId)
        );
        if (user == null) {
            throw new RuntimeException("用户不存在");
        }

        UserInfoDTO dto = new UserInfoDTO();
        dto.setUserId(user.getUserId());
        dto.setUsername(user.getUsername());
        dto.setEmail(user.getEmail());
        dto.setAvatar(user.getAvatar());
        dto.setRole(user.getRole());
        dto.setTotalStudyHours(user.getTotalStudyHours());
        dto.setTotalQuestionsAnswered(user.getTotalQuestionsAnswered());
        dto.setTotalCorrect(user.getTotalCorrect());
        if (user.getTotalQuestionsAnswered() != null && user.getTotalQuestionsAnswered() > 0) {
            dto.setAccuracy((double) user.getTotalCorrect() / user.getTotalQuestionsAnswered() * 100);
        }
        dto.setStudyStreak(user.getStudyStreak());
        if (user.getWeakAreas() != null) {
            dto.setWeakAreas(Arrays.asList(user.getWeakAreas().split(",")));
        }
        dto.setPreferredDirection(user.getPreferredDirection());
        dto.setLearningGoal(user.getLearningGoal());
        dto.setSkillLevel(user.getSkillLevel());

        try {
            Long masteredCount = questionProgressMapper.selectCount(
                new LambdaQueryWrapper<QuestionProgress>()
                    .eq(QuestionProgress::getUserId, userId)
                    .eq(QuestionProgress::getMastered, true)
            );
            dto.setMasteredCount(masteredCount != null ? masteredCount.intValue() : 0);

            Long pendingReviewCount = questionProgressMapper.selectCount(
                new LambdaQueryWrapper<QuestionProgress>()
                    .eq(QuestionProgress::getUserId, userId)
                    .eq(QuestionProgress::getMastered, true)
                    .isNotNull(QuestionProgress::getNextReviewTime)
                    .apply("next_review_time <= NOW()")
            );
            dto.setPendingReviewCount(pendingReviewCount != null ? pendingReviewCount.intValue() : 0);

            Long totalCards = knowledgeCardMapper.selectCount(null);
            dto.setTotalCards(totalCards != null ? totalCards.intValue() : 0);
        } catch (Exception e) {
            log.warn("获取统计信息失败: {}", e.getMessage());
        }

        return dto;
    }

    // 退出登录
    public void logout(String token) {
        tokenStore.remove(token);
    }
}
