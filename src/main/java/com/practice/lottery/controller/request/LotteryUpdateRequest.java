package com.practice.lottery.controller.request;


import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import lombok.Data;



import java.util.List;

@Data
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class LotteryUpdateRequest {
private Long eventId;

private Boolean isActive;

@Min(value = 0, message = "Remain amount must be >= 0")
private Integer remainAmount;

@Valid
private List<UpdatePrizeRateRequest> rateUpdateList;



}
