package com.practice.lottery.dao.repository;

import com.practice.lottery.dao.entity.UserLotteryQuota;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserLotteryQuotaRepository extends JpaRepository<UserLotteryQuota,Long> {
    Optional<UserLotteryQuota> findByUidAndLotteryEventId(Long uid , Long eventId);
}
