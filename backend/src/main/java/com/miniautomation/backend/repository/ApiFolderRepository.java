package com.miniautomation.backend.repository;

import com.miniautomation.backend.entity.ApiFolderEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ApiFolderRepository extends JpaRepository<ApiFolderEntity, Long> {
    List<ApiFolderEntity> findByCollectionIdOrderBySortOrderAsc(Long collectionId);
}
