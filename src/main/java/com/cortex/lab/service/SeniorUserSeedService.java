package com.cortex.lab.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cortex.lab.entity.*;
import com.cortex.lab.mapper.*;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Senior 用户初始化：启动时自动标记所有知识树节点和题目为已掌握，
 * 确保"资深"账号名副其实。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SeniorUserSeedService {

    private static final String SENIOR_USERNAME = "senior";

    private final UserMapper userMapper;
    private final AssistantConfigMapper configMapper;
    private final QuestionBankMapper questionBankMapper;
    private final QuestionProgressMapper questionProgressMapper;
    private final KnowledgeTreeService knowledgeTreeService;

    @PostConstruct
    public void seedSeniorUser() {
        try {
            User senior = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getUsername, SENIOR_USERNAME).last("LIMIT 1")
            );
            if (senior == null) {
                log.info("Senior 用户不存在，跳过初始化");
                return;
            }
            String seniorUserId = senior.getUserId();

            List<String> allNodeIds = knowledgeTreeService.getAllNodeIds();
            if (allNodeIds.isEmpty()) {
                log.warn("知识树节点为空，跳过 Senior 初始化");
                return;
            }

            List<String> masteredIds = knowledgeTreeService.getMasteredNodeIds(seniorUserId);
            if (masteredIds.size() >= allNodeIds.size()) {
                log.info("Senior 用户已初始化完成，跳过");
                return;
            }

            log.info("开始初始化 Senior 用户通关数据...");

            // 1. 将所有知识树节点标记为已掌握
            int nodeCount = 0;
            for (String nodeId : allNodeIds) {
                String key = "tree_mastered_" + seniorUserId + "_" + nodeId;
                List<AssistantConfig> existing = configMapper.selectList(
                    new LambdaQueryWrapper<AssistantConfig>()
                        .eq(AssistantConfig::getConfigKey, key)
                        .last("LIMIT 1")
                );
                if (existing.isEmpty()) {
                    AssistantConfig c = new AssistantConfig();
                    c.setConfigKey(key);
                    c.setConfigValue("true");
                    c.setConfigType("boolean");
                    c.setGmtCreate(LocalDateTime.now());
                    c.setGmtModified(LocalDateTime.now());
                    configMapper.insert(c);
                    nodeCount++;
                }
            }
            log.info("已标记 {} 个知识树节点为已掌握", nodeCount);

            // 2. 将所有题目标记为已掌握
            List<QuestionBank> allQuestions = questionBankMapper.selectList(null);
            int questionCount = 0;
            for (QuestionBank q : allQuestions) {
                QuestionProgress existing = questionProgressMapper.selectOne(
                    new LambdaQueryWrapper<QuestionProgress>()
                        .eq(QuestionProgress::getQuestionId, q.getId())
                        .eq(QuestionProgress::getUserId, seniorUserId)
                        .last("LIMIT 1")
                );
                if (existing == null) {
                    QuestionProgress p = new QuestionProgress();
                    p.setQuestionId(q.getId());
                    p.setUserId(seniorUserId);
                    p.setMastered(true);
                    p.setReviewCount(1);
                    p.setLastReviewTime(LocalDateTime.now());
                    p.setGmtCreate(LocalDateTime.now());
                    p.setGmtModified(LocalDateTime.now());
                    questionProgressMapper.insert(p);
                    questionCount++;
                }
            }
            log.info("已标记 {} 道题目为已掌握", questionCount);

            // 3. 更新用户学习统计
            int totalQuestions = allQuestions.size();
            senior.setTotalQuestionsAnswered(totalQuestions);
            senior.setTotalCorrect(totalQuestions);
            senior.setStudyStreak(365);
            senior.setLastStudyDate(LocalDate.now());
            senior.setSkillLevel("ADVANCED");
            senior.setGmtModified(LocalDateTime.now());
            userMapper.updateById(senior);
            log.info("Senior 用户统计已更新: 答题数={}, 正确率=100%", totalQuestions);

        } catch (Exception e) {
            log.error("初始化 Senior 用户数据失败", e);
        }
    }
}