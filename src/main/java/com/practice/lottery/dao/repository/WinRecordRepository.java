package com.practice.lottery.dao.repository;

import com.practice.lottery.dao.entity.WinRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface WinRecordRepository extends JpaRepository<WinRecord, Long> {

    List<WinRecord> findByUid(Long uid);
}
