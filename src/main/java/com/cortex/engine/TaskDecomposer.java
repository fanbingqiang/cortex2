package com.cortex.engine;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.cortex.agent.AgentRegistry;
import com.cortex.dto.TaskGraph;
import com.cortex.dto.TaskNode;
import com.cortex.entity.AgentMetadata;
import com.cortex.llm.LlmClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class TaskDecomposer {
    
    private final LlmClient llmClient;
    private final AgentRegistry agentRegistry;
    
    private static final String DECOMPOSITION_PROMPT = """
        你是一个任务分解专家。请将用户的需求分解为多个子任务，每个子任务应该：
        1. 明确具体，可以独立执行
        2. 标注需要的能力类型（如：code-analysis, search, report等）
        3. 标注依赖关系（哪些任务需要在其他任务完成后才能执行）
        
        请以JSON格式输出，格式如下：
        {
            "title": "任务总标题",
            "nodes": [
                {
                    "nodeId": "node_1",
                    "title": "子任务标题",
                    "description": "子任务详细描述",
                    "capability": "需要的能力类型",
                    "dependencies": []
                },
                {
                    "nodeId": "node_2",
                    "title": "子任务标题2",
                    "description": "子任务详细描述2",
                    "capability": "需要的能力类型",
                    "dependencies": ["node_1"]
                }
            ]
        }
        
        注意：
        - 只输出JSON，不要有其他文字
        - nodeId使用node_1, node_2, node_3...的格式
        - dependencies数组中填写依赖的nodeId
        - 确保任务之间的依赖关系正确，不能有循环依赖
        
        用户需求：
        """;
    
    public TaskGraph decompose(String userRequirement) {
        String response = llmClient.chatSimple(DECOMPOSITION_PROMPT + userRequirement);
        
        try {
            String jsonStr = extractJson(response);
            JSONObject json = JSON.parseObject(jsonStr);
            
            TaskGraph graph = new TaskGraph();
            graph.setTaskId(UUID.randomUUID().toString().replace("-", "").substring(0, 16));
            graph.setTitle(json.getString("title"));
            
            List<TaskNode> nodes = new ArrayList<>();
            JSONArray nodesArray = json.getJSONArray("nodes");
            
            for (int i = 0; i < nodesArray.size(); i++) {
                JSONObject nodeJson = nodesArray.getJSONObject(i);
                TaskNode node = new TaskNode();
                node.setNodeId(nodeJson.getString("nodeId"));
                node.setTitle(nodeJson.getString("title"));
                node.setDescription(nodeJson.getString("description"));
                node.setStatus("PENDING");
                
                String capability = nodeJson.getString("capability");
                String agentId = findBestAgent(capability);
                node.setAgentId(agentId);
                
                List<String> dependencies = nodeJson.getList("dependencies", String.class);
                node.setDependencies(dependencies != null ? dependencies : new ArrayList<>());
                
                nodes.add(node);
            }
            
            graph.setNodes(nodes);
            return graph;
            
        } catch (Exception e) {
            log.error("解析任务图失败: {}", response, e);
            return createSimpleGraph(userRequirement);
        }
    }
    
    private String extractJson(String response) {
        int start = response.indexOf("{");
        int end = response.lastIndexOf("}");
        if (start >= 0 && end > start) {
            return response.substring(start, end + 1);
        }
        return response;
    }
    
    private String findBestAgent(String capability) {
        List<AgentMetadata> agents = agentRegistry.findAgentsByCapability(capability);
        if (!agents.isEmpty()) {
            return agents.get(0).getAgentId();
        }
        return "search-agent";
    }
    
    private TaskGraph createSimpleGraph(String requirement) {
        TaskGraph graph = new TaskGraph();
        graph.setTaskId(UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        graph.setTitle(requirement);
        
        TaskNode node = new TaskNode();
        node.setNodeId("node_1");
        node.setTitle(requirement);
        node.setDescription(requirement);
        node.setAgentId("search-agent");
        node.setStatus("PENDING");
        node.setDependencies(new ArrayList<>());
        
        graph.setNodes(List.of(node));
        return graph;
    }
}
