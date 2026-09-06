package com.miniautomation.backend.repository;

import com.miniautomation.backend.entity.DataDrivenRunEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DataDrivenRunRepository extends JpaRepository<DataDrivenRunEntity, Long> {
    List<DataDrivenRunEntity> findByScenarioIdOrderByStartedAtDesc(Long scenarioId);
}
