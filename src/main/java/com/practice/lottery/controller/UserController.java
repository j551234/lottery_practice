package com.practice.lottery.controller;

import com.practice.lottery.controller.response.WinRecordResponse;
import com.practice.lottery.dao.entity.User;
import com.practice.lottery.dao.entity.WinRecord;
import com.practice.lottery.dto.ApiResponse;
import com.practice.lottery.service.LotteryService;
import com.practice.lottery.service.LotterySyncService;
import com.practice.lottery.service.WinRecordService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

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