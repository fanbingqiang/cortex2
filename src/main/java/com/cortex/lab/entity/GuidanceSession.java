package com.cortex.lab.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("guidance_session")
public class GuidanceSession {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String userId;
    private Long questionId;
    private String stage;
    private String selectedOption;
    private Integer hintCount;
    private Integer correctCount;
    private Integer wrongCount;
    private Boolean resolved;
    private LocalDateTime gmtCreate;
    private LocalDateTime gmtModified;
}
