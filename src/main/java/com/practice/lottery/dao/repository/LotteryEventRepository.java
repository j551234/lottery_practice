package com.practice.lottery.dao.repository;

import com.practice.lottery.dao.entity.LotteryEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LotteryEventRepository extends JpaRepository<LotteryEvent, Long > {
}
