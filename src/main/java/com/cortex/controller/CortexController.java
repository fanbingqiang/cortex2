package com.cortex.controller;

import com.alibaba.fastjson2.JSON;
import com.cortex.agent.AgentRegistry;
import com.cortex.dto.ApiResponse;
import com.cortex.dto.TaskGraph;
import com.cortex.dto.UserProfileDto;
import com.cortex.engine.TaskDecomposer;
import com.cortex.engine.CortexTaskExecutor;
import com.cortex.entity.AgentMetadata;
import com.cortex.entity.Task;
import com.cortex.mapper.TaskMapper;
import com.cortex.service.MemoryService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class CortexController {
    
    private final TaskDecomposer taskDecomposer;
    private final CortexTaskExecutor cortexTaskExecutor;
    private final AgentRegistry agentRegistry;
    private final MemoryService memoryService;
    private final TaskMapper taskMapper;
    
    @PostMapping("/task/create")
    public ApiResponse<TaskGraph> createTask(@RequestBody CreateTaskRequest request) {
        try {
            TaskGraph graph = taskDecomposer.decompose(request.getRequirement());
            
            Task task = new Task();
            task.setTaskId(graph.getTaskId());
            task.setUserId(request.getUserId());
            task.setTitle(graph.getTitle());
            task.setDescription(request.getRequirement());
            task.setStatus("CREATED");
            task.setTaskGraph(JSON.toJSONString(graph));
            task.setGmtCreate(LocalDateTime.now());
            task.setGmtModified(LocalDateTime.now());
            taskMapper.insert(task);
            
            return ApiResponse.success("任务创建成功", graph);
        } catch (Exception e) {
            log.error("创建任务失败", e);
            return ApiResponse.error("创建任务失败: " + e.getMessage());
        }
    }
    
    @PostMapping("/task/execute")
    public ApiResponse<TaskGraph> executeTask(@RequestBody ExecuteTaskRequest request) {
        try {
            Task task = taskMapper.selectById(request.getTaskId());
            if (task == null) {
                return ApiResponse.error("任务不存在");
            }
            
            TaskGraph graph = JSON.parseObject(task.getTaskGraph(), TaskGraph.class);
            graph = cortexTaskExecutor.execute(graph, request.getUserId());
            
            String result = cortexTaskExecutor.aggregateResult(graph);
            task.setTaskGraph(JSON.toJSONString(graph));
            task.setResult(result);
            task.setStatus("COMPLETED");
            task.setGmtModified(LocalDateTime.now());
            taskMapper.updateById(task);
            
            return ApiResponse.success("任务执行成功", graph);
        } catch (Exception e) {
            log.error("执行任务失败", e);
            return ApiResponse.error("执行任务失败: " + e.getMessage());
        }
    }
    
    @PostMapping("/task/run")
    public ApiResponse<Map<String, Object>> runTask(@RequestBody CreateTaskRequest request) {
        try {
            TaskGraph graph = taskDecomposer.decompose(request.getRequirement());
            
            Task task = new Task();
            task.setTaskId(graph.getTaskId());
            task.setUserId(request.getUserId());
            task.setTitle(graph.getTitle());
            task.setDescription(request.getRequirement());
            task.setStatus("RUNNING");
            task.setTaskGraph(JSON.toJSONString(graph));
            task.setGmtCreate(LocalDateTime.now());
            task.setGmtModified(LocalDateTime.now());
            taskMapper.insert(task);
            
            graph = cortexTaskExecutor.execute(graph, request.getUserId());
            
            String result = cortexTaskExecutor.aggregateResult(graph);
            task.setTaskGraph(JSON.toJSONString(graph));
            task.setResult(result);
            task.setStatus("COMPLETED");
            task.setGmtModified(LocalDateTime.now());
            taskMapper.updateById(task);
            
            return ApiResponse.success("任务执行成功", Map.of(
                "taskId", graph.getTaskId(),
                "title", graph.getTitle(),
                "graph", graph,
                "result", result
            ));
        } catch (Exception e) {
            log.error("执行任务失败", e);
            return ApiResponse.error("执行任务失败: " + e.getMessage());
        }
    }
    
    @GetMapping("/task/{taskId}")
    public ApiResponse<Task> getTask(@PathVariable String taskId) {
        Task task = taskMapper.selectById(taskId);
        if (task == null) {
            return ApiResponse.error("任务不存在");
        }
        return ApiResponse.success(task);
    }
    
    @GetMapping("/agents")
    public ApiResponse<List<AgentMetadata>> listAgents() {
        return ApiResponse.success(agentRegistry.getAllAgents());
    }
    
    @GetMapping("/profile/{userId}")
    public ApiResponse<UserProfileDto> getProfile(@PathVariable String userId) {
        UserProfileDto profile = memoryService.getProfile(userId);
        if (profile == null) {
            memoryService.getOrCreateProfile(userId, "用户");
            profile = memoryService.getProfile(userId);
        }
        return ApiResponse.success(profile);
    }
    
    @PostMapping("/profile/{userId}/tags")
    public ApiResponse<String> addPersonalityTag(@PathVariable String userId, @RequestBody Map<String, String> body) {
        String tag = body.get("tag");
        memoryService.addPersonalityTag(userId, tag);
        return ApiResponse.success("添加成功");
    }
    
    @PostMapping("/profile/{userId}/habits")
    public ApiResponse<String> addWorkHabit(@PathVariable String userId, @RequestBody Map<String, String> body) {
        String habit = body.get("habit");
        memoryService.addWorkHabit(userId, habit);
        return ApiResponse.success("添加成功");
    }
    
    @PostMapping("/profile/{userId}/mistakes")
    public ApiResponse<String> addMistake(@PathVariable String userId, @RequestBody Map<String, String> body) {
        memoryService.addMistake(userId, body.get("keyword"), body.get("description"), body.get("taskId"));
        return ApiResponse.success("添加成功");
    }
    
    @Data
    public static class CreateTaskRequest {
        private String userId;
        private String requirement;
    }
    
    @Data
    public static class ExecuteTaskRequest {
        private String taskId;
        private String userId;
    }
}
