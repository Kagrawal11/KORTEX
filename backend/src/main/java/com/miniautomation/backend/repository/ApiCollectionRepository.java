package com.miniautomation.backend.repository;

import com.miniautomation.backend.entity.ApiCollectionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ApiCollectionRepository extends JpaRepository<ApiCollectionEntity, Long> {
}
