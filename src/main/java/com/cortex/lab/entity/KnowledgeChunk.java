package com.cortex.lab.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("knowledge_chunk")
public class KnowledgeChunk {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String chunkKey;
    private String content;
    private String keywords;
    private String source;
    private Long sourceId;
    private Integer weight;
    private LocalDateTime gmtCreate;
    private LocalDateTime gmtModified;
}
