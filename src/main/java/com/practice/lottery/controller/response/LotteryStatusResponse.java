package com.practice.lottery.controller.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

import java.math.BigDecimal;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class LotteryStatusResponse {
    private Long eventId;
    private String eventName;
    private Boolean isActive;
    private Long remainAmount;
    private BigDecimal totalRate;
    private List<PrizeInfo> prizes;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public static class PrizeInfo {
        private Long prizeId;
        private String prizeName;
        private BigDecimal rate;
        private Long stock;
    }
}
