package com.miniautomation.backend.repository;

import com.miniautomation.backend.entity.ApiRequestEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ApiRequestRepository extends JpaRepository<ApiRequestEntity, Long> {
    List<ApiRequestEntity> findByCollectionIdOrderBySortOrderAsc(Long collectionId);
    List<ApiRequestEntity> findByFolderIdOrderBySortOrderAsc(Long folderId);
}
