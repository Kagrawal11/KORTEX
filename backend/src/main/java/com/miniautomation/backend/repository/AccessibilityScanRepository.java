package com.miniautomation.backend.repository;

import com.miniautomation.backend.entity.AccessibilityScanEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AccessibilityScanRepository extends JpaRepository<AccessibilityScanEntity, Long> {
}
