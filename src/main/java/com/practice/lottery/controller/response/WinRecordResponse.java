package com.practice.lottery.controller.response;


import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class WinRecordResponse {
    private Long id;
    private Long lotteryEventId;
    private String eventName;
    private Long uid;
    private Long drawPrizeId;
    private String prizeName;
    private Integer remainPrizeAmount;
    private LocalDateTime createdTime;
}
