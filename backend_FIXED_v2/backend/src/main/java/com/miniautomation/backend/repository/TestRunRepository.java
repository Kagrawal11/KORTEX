package com.miniautomation.backend.repository;

import com.miniautomation.backend.entity.TestRunEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TestRunRepository extends JpaRepository<TestRunEntity, Long> {
    List<TestRunEntity> findByScenarioIdOrderByStartedAtDesc(Long scenarioId);
}
