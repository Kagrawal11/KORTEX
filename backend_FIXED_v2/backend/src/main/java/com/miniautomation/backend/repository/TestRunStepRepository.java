package com.miniautomation.backend.repository;

import com.miniautomation.backend.entity.TestRunStepEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface TestRunStepRepository extends JpaRepository<TestRunStepEntity, Long> {
}
