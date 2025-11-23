package com.practice.lottery.controller;

import com.practice.lottery.controller.response.WinRecordResponse;
import com.practice.lottery.dao.entity.User;
import com.practice.lottery.dto.ApiResponse;
import com.practice.lottery.service.LotteryService;
import com.practice.lottery.service.LotterySyncService;
import com.practice.lottery.service.WinRecordService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/user")
@RequiredArgsConstructor
public class UserController {
    private final LotteryService lotteryService;
    private final LotterySyncService lotterySyncService;
    private final WinRecordService winRecordService;

    @GetMapping("/test")
    public ResponseEntity<ApiResponse<String>> test(Authentication auth) {
        User user = (User) auth.getPrincipal();
        return ResponseEntity.ok(
                ApiResponse.success("Authentication verified", user.getUsername())
        );
    }

    @PostMapping("/event/{eventId}/draw")
    public ResponseEntity<ApiResponse<Map<String, Object>>> draw(
            Authentication auth,
            @PathVariable Long eventId
    ) {
        User user = (User) auth.getPrincipal();
        String result = lotteryService.drawRedis(eventId, user.getId(), true);
        lotterySyncService.syncUserQuota(eventId, user.getId());
        Map<String, Object> data = Map.of(
                "prize", result,
                "is_winner", !"Miss".equals(result)
        );

        return ResponseEntity.ok(
                ApiResponse.success("Lottery drawn successfully", data)
        );
    }

    @PostMapping("/event/{eventId}/multi-draw")
    public ResponseEntity<List<ApiResponse<?>>> multiDraw(
            Authentication auth,
            @PathVariable Long eventId,
            @RequestParam(name ="times") @Valid @Min(value = 1, message = "times must be greater than 0") Integer times
            ) {
        User user = (User) auth.getPrincipal();
        List<Object> hitList = new ArrayList<>();
        for (int i = 0 ; i<times;i++) {
            String result = lotteryService.drawRedis(eventId, user.getId(), true);
            lotterySyncService.syncUserQuota(eventId, user.getId());
            Map<String, Object> data = Map.of(
                    "prize", result,
                    "is_winner", !"Miss".equals(result)
            );
            hitList.add(data);
        }

        return ResponseEntity.ok(
                Collections.singletonList(ApiResponse.success("Lottery drawn successfully", hitList))
        );
    }


    /**
     * Get all win records for a user
     */
    @GetMapping("/my-records")
    public ResponseEntity<ApiResponse<List<WinRecordResponse>>> getMyWinRecords(
            Authentication auth
    ) {
        User user = (User) auth.getPrincipal();
        List<WinRecordResponse> records = winRecordService.getUserWinRecords(user.getId());
        return ResponseEntity.ok(
                ApiResponse.success("Win records retrieved successfully", records)
        );
    }
}