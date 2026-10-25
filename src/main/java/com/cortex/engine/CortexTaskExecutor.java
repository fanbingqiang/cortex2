package com.cortex.engine;

import com.alibaba.fastjson2.JSON;
import com.cortex.agent.AgentRegistry;
import com.cortex.dto.TaskGraph;
import com.cortex.dto.TaskNode;
import com.cortex.entity.Task;
import com.cortex.entity.TaskExecutionLog;
import com.cortex.mapper.TaskExecutionLogMapper;
import com.cortex.mapper.TaskMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class CortexTaskExecutor {
    
    private final AgentRegistry agentRegistry;
    private final TaskMapper taskMapper;
    private final TaskExecutionLogMapper executionLogMapper;
    
    public TaskGraph execute(TaskGraph graph, String userId) {
        Map<String, TaskNode> nodeMap = new ConcurrentHashMap<>();
        graph.getNodes().forEach(node -> nodeMap.put(node.getNodeId(), node));
        
        List<String> executionOrder = topologicalSort(graph);
        
        for (String nodeId : executionOrder) {
            TaskNode node = nodeMap.get(nodeId);
            
            if (!canExecute(node, nodeMap)) {
                node.setStatus("SKIPPED");
                continue;
            }
            
            String input = prepareInput(node, nodeMap);
            node.setInput(input);
            
            long startTime = System.currentTimeMillis();
            try {
                node.setStatus("RUNNING");
                String output = agentRegistry.executeAgent(node.getAgentId(), input, userId);
                node.setOutput(output);
                node.setStatus("SUCCESS");
                
                logExecution(graph.getTaskId(), node, input, output, "SUCCESS", null, 
                    System.currentTimeMillis() - startTime);
                
            } catch (Exception e) {
                node.setStatus("FAILED");
                log.error("执行任务节点失败: nodeId={}, error={}", nodeId, e.getMessage());
                
                logExecution(graph.getTaskId(), node, input, null, "FAILED", e.getMessage(),
                    System.currentTimeMillis() - startTime);
            }
        }
        
        return graph;
    }
    
    private List<String> topologicalSort(TaskGraph graph) {
        Map<String, Integer> inDegree = new HashMap<>();
        Map<String, List<String>> adjacency = new HashMap<>();
        
        for (TaskNode node : graph.getNodes()) {
            inDegree.putIfAbsent(node.getNodeId(), 0);
            adjacency.putIfAbsent(node.getNodeId(), new ArrayList<>());
            
            for (String dep : node.getDependencies()) {
                adjacency.computeIfAbsent(dep, k -> new ArrayList<>()).add(node.getNodeId());
                inDegree.merge(node.getNodeId(), 1, Integer::sum);
            }
        }
        
        Queue<String> queue = new LinkedList<>();
        for (Map.Entry<String, Integer> entry : inDegree.entrySet()) {
            if (entry.getValue() == 0) {
                queue.offer(entry.getKey());
            }
        }
        
        List<String> result = new ArrayList<>();
        while (!queue.isEmpty()) {
            String current = queue.poll();
            result.add(current);
            
            for (String neighbor : adjacency.getOrDefault(current, new ArrayList<>())) {
                int newDegree = inDegree.get(neighbor) - 1;
                inDegree.put(neighbor, newDegree);
                if (newDegree == 0) {
                    queue.offer(neighbor);
                }
            }
        }
        
        return result;
    }
    
    private boolean canExecute(TaskNode node, Map<String, TaskNode> nodeMap) {
        for (String depId : node.getDependencies()) {
            TaskNode depNode = nodeMap.get(depId);
            if (depNode == null || !"SUCCESS".equals(depNode.getStatus())) {
                return false;
            }
        }
        return true;
    }
    
    private String prepareInput(TaskNode node, Map<String, TaskNode> nodeMap) {
        StringBuilder input = new StringBuilder();
        input.append("任务：").append(node.getTitle()).append("\n");
        input.append("描述：").append(node.getDescription()).append("\n");
        
        if (!node.getDependencies().isEmpty()) {
            input.append("\n前置任务结果：\n");
            for (String depId : node.getDependencies()) {
                TaskNode depNode = nodeMap.get(depId);
                if (depNode != null && depNode.getOutput() != null) {
                    input.append("【").append(depNode.getTitle()).append("】\n");
                    input.append(depNode.getOutput()).append("\n\n");
                }
            }
        }
        
        return input.toString();
    }
    
    private void logExecution(String taskId, TaskNode node, String input, String output, 
                              String status, String errorMessage, long durationMs) {
        TaskExecutionLog log = new TaskExecutionLog();
        log.setTaskId(taskId);
        log.setNodeId(node.getNodeId());
        log.setAgentId(node.getAgentId());
        log.setInput(input);
        log.setOutput(output);
        log.setStatus(status);
        log.setErrorMessage(errorMessage);
        log.setDurationMs(durationMs);
        log.setGmtCreate(LocalDateTime.now());
        
        executionLogMapper.insert(log);
    }
    
    public String aggregateResult(TaskGraph graph) {
        StringBuilder result = new StringBuilder();
        result.append("# ").append(graph.getTitle()).append("\n\n");
        
        for (TaskNode node : graph.getNodes()) {
            if ("SUCCESS".equals(node.getStatus()) && node.getOutput() != null) {
                result.append("## ").append(node.getTitle()).append("\n");
                result.append(node.getOutput()).append("\n\n");
            }
        }
        
        return result.toString();
    }
}
