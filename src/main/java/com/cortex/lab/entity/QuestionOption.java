package com.cortex.lab.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("question_option")
public class QuestionOption {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long questionId;
    private String optionLabel;
    private String optionText;
    private Boolean isCorrect;
    private String guidance;
    private Integer hintLevel;
    private Integer orderNum;
    private LocalDateTime gmtCreate;
}
