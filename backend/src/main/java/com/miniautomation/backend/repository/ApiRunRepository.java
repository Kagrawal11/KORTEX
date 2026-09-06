package com.miniautomation.backend.repository;

import com.miniautomation.backend.entity.ApiRunEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ApiRunRepository extends JpaRepository<ApiRunEntity, Long> {
    List<ApiRunEntity> findByCollectionIdOrderByStartedAtDesc(Long collectionId);
    List<ApiRunEntity> findAllByOrderByStartedAtDesc();
}
