package com.practice.lottery.dao.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
@Data
@AllArgsConstructor
@NoArgsConstructor
@Entity(name = "user_lottery_quota")
public class UserLotteryQuota {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Integer uid;

    private Long lotteryEventId;

    private Integer drawQuota;

    private LocalDateTime createdTime;

    private LocalDateTime updatedTime;
}
