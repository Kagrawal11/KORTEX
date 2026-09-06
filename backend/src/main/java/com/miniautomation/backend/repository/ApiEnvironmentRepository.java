package com.miniautomation.backend.repository;

import com.miniautomation.backend.entity.ApiEnvironmentEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ApiEnvironmentRepository extends JpaRepository<ApiEnvironmentEntity, Long> {
}
