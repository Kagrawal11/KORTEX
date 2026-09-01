package com.miniautomation.backend.repository;

import com.miniautomation.backend.entity.TestStepEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TestStepRepository extends JpaRepository<TestStepEntity, Long> {
}
