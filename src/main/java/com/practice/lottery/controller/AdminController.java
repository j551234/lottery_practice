package com.practice.lottery.controller;

import com.practice.lottery.controller.request.LotteryUpdateRequest;
import com.practice.lottery.controller.request.UpdatePrizeRateRequest;
import com.practice.lottery.controller.response.LotteryEventResponse;
import com.practice.lottery.controller.response.LotteryStatusResponse;
import com.practice.lottery.dto.ApiResponse;
import com.practice.lottery.service.LotteryManagementService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;


@RestController
@RequestMapping("/admin")
@RequiredArgsConstructor
public class AdminController {
    private final LotteryManagementService lotteryManagementService;


    /**
     * Get all lottery events (simple list)
     */
    @GetMapping("/events")
    public ResponseEntity<ApiResponse<List<LotteryEventResponse>>> getAllEvents() {
        List<LotteryEventResponse> events = lotteryManagementService.getAllEvents();
        return ResponseEntity.ok(
                ApiResponse.success("Events retrieved successfully", events)
        );
    }



    /**
     * Batch update lottery rates and active status
     */
    @PutMapping("/update")
    public ResponseEntity<ApiResponse<Map<String, Object>>> updateLotteryRates(
            @RequestBody @Valid LotteryUpdateRequest req
    ) {
        lotteryManagementService.updateLotteryRates(req);

        Map<String, Object> data = Map.of(
                "event_id", req.getEventId(),
                "updated_count", req.getRateUpdateList() != null ? req.getRateUpdateList().size() : 0,
                "is_active", req.getIsActive() != null ? req.getIsActive() : "unchanged"
        );

        return ResponseEntity.ok(
                ApiResponse.success("Lottery updated successfully", data)
        );
    }

    /**
     * Update single prize rate
     */
    @PutMapping("/event/{eventId}/prize/{prizeId}/rate")
    public ResponseEntity<ApiResponse<Map<String, Object>>> updateSinglePrizeRate(
            @PathVariable Long eventId,
            @PathVariable Long prizeId,
            @RequestBody  UpdatePrizeRateRequest req
    ) {
        lotteryManagementService.updateSinglePrizeRate(eventId, prizeId, req.getRate());

        Map<String, Object> data = Map.of(
                "event_id", eventId,
                "prize_id", prizeId,
                "new_rate", req.getRate().toPlainString()
        );

        return ResponseEntity.ok(
                ApiResponse.success("Prize rate updated successfully", data)
        );
    }

    /**
     * Refresh entire lottery cache from database
     */
    @PostMapping("/event/{eventId}/refresh-cache")
    public ResponseEntity<ApiResponse<Map<String, Object>>> refreshLotteryCache(
            @PathVariable Long eventId
    ) {
        lotteryManagementService.refreshLotteryCache(eventId);

        Map<String, Object> data = Map.of(
                "event_id", eventId,
                "cache_refreshed", true
        );

        return ResponseEntity.ok(
                ApiResponse.success("Cache refreshed successfully", data)
        );
    }

    /**
     * Clear lottery cache (admin only)
     */
    @DeleteMapping("/event/{eventId}/clear-cache")
    public ResponseEntity<ApiResponse<Map<String, Object>>> clearLotteryCache(
            @PathVariable Long eventId
    ) {
        lotteryManagementService.clearLotteryCache(eventId);

        Map<String, Object> data = Map.of(
                "event_id", eventId,
                "cache_cleared", true
        );

        return ResponseEntity.ok(
                ApiResponse.success("Cache cleared successfully", data)
        );
    }

    /**
     * Get lottery event status with all prize rates and stocks
     */
    @GetMapping("/event/{eventId}/status")
    public ResponseEntity<ApiResponse<LotteryStatusResponse>> getLotteryStatus(
            @PathVariable Long eventId
    ) {
        LotteryStatusResponse status = lotteryManagementService.getLotteryStatus(eventId);

        return ResponseEntity.ok(
                ApiResponse.success("Lottery status retrieved successfully", status)
        );
    }
}