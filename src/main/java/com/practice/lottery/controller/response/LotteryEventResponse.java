package com.practice.lottery.controller.response;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;


import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class LotteryEventResponse {
    private Long id;
    private String name;
    private Integer settingAmount;
    private Integer remainAmount;
    private Boolean isActive;
    private LocalDateTime createdTime;
    private LocalDateTime updatedTime;
}