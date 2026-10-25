package com.cortex.service;

import com.alibaba.fastjson2.JSON;
import com.cortex.dto.UserProfileDto;
import com.cortex.entity.MistakeRecord;
import com.cortex.entity.UserProfile;
import com.cortex.mapper.MistakeRecordMapper;
import com.cortex.mapper.UserProfileMapper;
import cn.hutool.core.util.StrUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class MemoryService {
    
    private final UserProfileMapper userProfileMapper;
    private final MistakeRecordMapper mistakeRecordMapper;
    
    public UserProfile getOrCreateProfile(String userId, String username) {
        UserProfile profile = userProfileMapper.selectById(userId);
        if (profile == null) {
            profile = new UserProfile();
            profile.setUserId(userId);
            profile.setUsername(username);
            profile.setPersonalityTags("[]");
            profile.setWorkHabits("[]");
            profile.setPreferences("{}");
            profile.setGmtCreate(LocalDateTime.now());
            profile.setGmtModified(LocalDateTime.now());
            userProfileMapper.insert(profile);
        }
        return profile;
    }
    
    public UserProfileDto getProfile(String userId) {
        UserProfile profile = userProfileMapper.selectById(userId);
        if (profile == null) {
            return null;
        }
        
        UserProfileDto dto = new UserProfileDto();
        dto.setUserId(profile.getUserId());
        dto.setUsername(profile.getUsername());
        
        if (StrUtil.isNotBlank(profile.getPersonalityTags())) {
            dto.setPersonalityTags(JSON.parseArray(profile.getPersonalityTags(), String.class));
        } else {
            dto.setPersonalityTags(new ArrayList<>());
        }
        
        if (StrUtil.isNotBlank(profile.getWorkHabits())) {
            dto.setWorkHabits(JSON.parseArray(profile.getWorkHabits(), String.class));
        } else {
            dto.setWorkHabits(new ArrayList<>());
        }
        
        List<MistakeRecord> mistakes = mistakeRecordMapper.selectList(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<MistakeRecord>()
                .eq(MistakeRecord::getUserId, userId)
                .orderByDesc(MistakeRecord::getGmtCreate)
                .last("LIMIT 10")
        );
        
        List<UserProfileDto.MistakeDto> mistakeDtos = new ArrayList<>();
        for (MistakeRecord m : mistakes) {
            UserProfileDto.MistakeDto md = new UserProfileDto.MistakeDto();
            md.setKeyword(m.getKeyword());
            md.setDescription(m.getDescription());
            mistakeDtos.add(md);
        }
        dto.setMistakes(mistakeDtos);
        
        return dto;
    }
    
    public void addPersonalityTag(String userId, String tag) {
        UserProfile profile = getOrCreateProfile(userId, "用户");
        List<String> tags = JSON.parseArray(profile.getPersonalityTags(), String.class);
        if (tags == null) {
            tags = new ArrayList<>();
        }
        if (!tags.contains(tag)) {
            tags.add(tag);
            profile.setPersonalityTags(JSON.toJSONString(tags));
            profile.setGmtModified(LocalDateTime.now());
            userProfileMapper.updateById(profile);
        }
    }
    
    public void addWorkHabit(String userId, String habit) {
        UserProfile profile = getOrCreateProfile(userId, "用户");
        List<String> habits = JSON.parseArray(profile.getWorkHabits(), String.class);
        if (habits == null) {
            habits = new ArrayList<>();
        }
        if (!habits.contains(habit)) {
            habits.add(habit);
            profile.setWorkHabits(JSON.toJSONString(habits));
            profile.setGmtModified(LocalDateTime.now());
            userProfileMapper.updateById(profile);
        }
    }
    
    public void addMistake(String userId, String keyword, String description, String taskId) {
        MistakeRecord record = new MistakeRecord();
        record.setUserId(userId);
        record.setKeyword(keyword);
        record.setDescription(description);
        record.setTaskId(taskId);
        record.setGmtCreate(LocalDateTime.now());
        mistakeRecordMapper.insert(record);
    }
    
    public void updateProfile(String userId, List<String> personalityTags, List<String> workHabits) {
        UserProfile profile = getOrCreateProfile(userId, "用户");
        if (personalityTags != null) {
            profile.setPersonalityTags(JSON.toJSONString(personalityTags));
        }
        if (workHabits != null) {
            profile.setWorkHabits(JSON.toJSONString(workHabits));
        }
        profile.setGmtModified(LocalDateTime.now());
        userProfileMapper.updateById(profile);
    }
}
