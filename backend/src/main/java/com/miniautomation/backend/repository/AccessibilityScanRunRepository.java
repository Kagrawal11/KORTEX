package com.miniautomation.backend.repository;

import com.miniautomation.backend.entity.AccessibilityScanRunEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AccessibilityScanRunRepository extends JpaRepository<AccessibilityScanRunEntity, Long> {
    List<AccessibilityScanRunEntity> findByScanIdOrderByStartedAtDesc(Long scanId);
    List<AccessibilityScanRunEntity> findAllByOrderByStartedAtDesc();
}
