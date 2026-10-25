package com.cortex.lab.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
public class KnowledgeNodeDTO {
    private String id;          // 节点唯一标识
    private String name;        // 节点名称
    private String description; // 节点描述
    private String path;        // 知识树路径，如 "Redis篇/Redis分布式锁"
    private List<KnowledgeNodeDTO> children; // 子节点
    private boolean leaf;       // 是否为叶子节点
    private String type; // null=陷阱代码, "project"=Spring Boot项目

    /** 不含 path 的构造函数，path 由 setPaths() 递归设置 */
    public KnowledgeNodeDTO(String id, String name, String description, List<KnowledgeNodeDTO> children, boolean leaf, String type) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.children = children;
        this.leaf = leaf;
        this.type = type;
    }
}