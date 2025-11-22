package com.practice.lottery.controller.request;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class UpdatePrizeRateRequest {

    private Long id;

    @NotNull(message = "Rate cannot be null")
    @DecimalMin(value = "0.0", inclusive = true, message = "Rate must be >= 0")
    @DecimalMax(value = "1.0", inclusive = true, message = "Rate must be <= 1")
    @Digits(integer = 1, fraction = 2, message = "Rate must have at most 2 decimal places")
    private BigDecimal rate;
}
