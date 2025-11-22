package com.practice.lottery.dao.entity;

import com.practice.lottery.controller.response.LotteryEventResponse;
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
@Entity(name = "lottery_event")
public class LotteryEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id ;

    private String name;

    private Boolean isActive;

    private Integer settingAmount;

    private Integer remainAmount;

    private LocalDateTime createdTime;

    private LocalDateTime updatedTime;



}
