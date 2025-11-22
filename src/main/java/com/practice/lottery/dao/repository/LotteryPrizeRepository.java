package com.practice.lottery.dao.repository;

import com.practice.lottery.dao.entity.LotteryPrize;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface LotteryPrizeRepository extends JpaRepository<LotteryPrize,Long> {

    List<LotteryPrize> findByLotteryEventId(Long lotteryEventId);

    Optional<LotteryPrize> findByLotteryEventIdAndName(Long eventId, String prizeName);
}
