package com.miniautomation.backend.repository;

import com.miniautomation.backend.entity.TestScenarioEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TestScenarioRepository extends JpaRepository<TestScenarioEntity, Long> {
}
