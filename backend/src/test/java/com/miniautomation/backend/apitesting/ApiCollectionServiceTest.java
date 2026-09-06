package com.miniautomation.backend.apitesting;

import com.miniautomation.backend.apitesting.dto.ApiRequestSpec;
import com.miniautomation.backend.apitesting.dto.AssertionDefinition;
import com.miniautomation.backend.apitesting.dto.KeyValueItem;
import com.miniautomation.backend.entity.ApiCollectionEntity;
import com.miniautomation.backend.entity.ApiFolderEntity;
import com.miniautomation.backend.entity.ApiRequestEntity;
import com.miniautomation.backend.repository.ApiCollectionRepository;
import com.miniautomation.backend.repository.ApiFolderRepository;
import com.miniautomation.backend.repository.ApiRequestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Coverage for the CRUD + Spec<->Entity JSON conversion at the heart of
 * ApiCollectionService — the round-trip (toSpec after applySpec) is the
 * single most important thing to verify here, since every saved request's
 * editable detail flows through it.
 */
class ApiCollectionServiceTest {

    private ApiCollectionRepository collectionRepository;
    private ApiFolderRepository folderRepository;
    private ApiRequestRepository requestRepository;
    private ApiCollectionService service;

    @BeforeEach
    void setUp() {
        collectionRepository = mock(ApiCollectionRepository.class);
        folderRepository = mock(ApiFolderRepository.class);
        requestRepository = mock(ApiRequestRepository.class);
        service = new ApiCollectionService(collectionRepository, folderRepository, requestRepository);

        when(collectionRepository.save(any(ApiCollectionEntity.class))).thenAnswer(inv -> {
            ApiCollectionEntity e = inv.getArgument(0);
            if (e.getId() == null) e.setId(1L);
            return e;
        });
        when(requestRepository.save(any(ApiRequestEntity.class))).thenAnswer(inv -> {
            ApiRequestEntity e = inv.getArgument(0);
            if (e.getId() == null) e.setId(100L);
            return e;
        });
        when(folderRepository.save(any(ApiFolderEntity.class))).thenAnswer(inv -> {
            ApiFolderEntity e = inv.getArgument(0);
            if (e.getId() == null) e.setId(10L);
            return e;
        });
        when(requestRepository.findByCollectionIdOrderBySortOrderAsc(any())).thenReturn(List.of());
    }

    @Test
    void createCollection_rejectsBlankName() {
        assertThatThrownBy(() -> service.createCollection("  ", "desc"))
                .isInstanceOf(ApiTestingException.class)
                .hasMessageContaining("name is required");
    }

    @Test
    void createRequest_thenToSpec_roundTripsEveryField() {
        ApiCollectionEntity collection = new ApiCollectionEntity();
        collection.setId(1L);
        when(collectionRepository.findById(1L)).thenReturn(Optional.of(collection));

        ApiRequestSpec spec = new ApiRequestSpec();
        spec.setName("Get Users");
        spec.setMethod("get"); // lowercase on the way in — must be normalised
        spec.setUrl("{{baseUrl}}/users");
        spec.setParams(List.of(new KeyValueItem("limit", "10")));
        spec.setHeaders(List.of(new KeyValueItem("Accept", "application/json")));
        spec.setBodyType("json");
        spec.setBodyContent("{\"a\":1}");
        AssertionDefinition assertion = new AssertionDefinition();
        assertion.setType("STATUS_CODE_EQUALS");
        assertion.setExpected("200");
        spec.setAssertions(List.of(assertion));

        ApiRequestEntity saved = service.createRequest(1L, null, spec);

        assertThat(saved.getMethod()).isEqualTo("GET");
        assertThat(saved.getBodyType()).isEqualTo("JSON");
        assertThat(saved.getParamsJson()).contains("limit").contains("10");

        ApiRequestSpec roundTripped = service.toSpec(saved);
        assertThat(roundTripped.getName()).isEqualTo("Get Users");
        assertThat(roundTripped.getMethod()).isEqualTo("GET");
        assertThat(roundTripped.getUrl()).isEqualTo("{{baseUrl}}/users");
        assertThat(roundTripped.getParams()).hasSize(1);
        assertThat(roundTripped.getParams().get(0).getKey()).isEqualTo("limit");
        assertThat(roundTripped.getHeaders()).hasSize(1);
        assertThat(roundTripped.getBodyContent()).isEqualTo("{\"a\":1}");
        assertThat(roundTripped.getAssertions()).hasSize(1);
        assertThat(roundTripped.getAssertions().get(0).getType()).isEqualTo("STATUS_CODE_EQUALS");
    }

    @Test
    void createRequest_rejectsUnsupportedMethod() {
        ApiCollectionEntity collection = new ApiCollectionEntity();
        collection.setId(1L);
        when(collectionRepository.findById(1L)).thenReturn(Optional.of(collection));

        ApiRequestSpec spec = new ApiRequestSpec();
        spec.setName("Bad");
        spec.setMethod("TRACE");

        assertThatThrownBy(() -> service.createRequest(1L, null, spec))
                .isInstanceOf(ApiTestingException.class)
                .hasMessageContaining("Unsupported HTTP method");
    }

    @Test
    void deleteFolder_movesItsRequestsToCollectionRootRatherThanDeletingThem() {
        ApiFolderEntity folder = new ApiFolderEntity();
        folder.setId(5L);
        when(folderRepository.findById(5L)).thenReturn(Optional.of(folder));

        ApiRequestEntity request = new ApiRequestEntity();
        request.setId(1L);
        request.setFolder(folder);
        when(requestRepository.findByFolderIdOrderBySortOrderAsc(5L)).thenReturn(List.of(request));

        service.deleteFolder(5L);

        assertThat(request.getFolder()).isNull();
    }

    @Test
    void moveRequest_rejectsMovingIntoAFolderFromADifferentCollection() {
        ApiCollectionEntity collectionA = new ApiCollectionEntity();
        collectionA.setId(1L);
        ApiCollectionEntity collectionB = new ApiCollectionEntity();
        collectionB.setId(2L);

        ApiRequestEntity request = new ApiRequestEntity();
        request.setId(1L);
        request.setCollection(collectionA);
        when(requestRepository.findById(1L)).thenReturn(Optional.of(request));

        ApiFolderEntity folderInB = new ApiFolderEntity();
        folderInB.setId(9L);
        folderInB.setCollection(collectionB);
        when(folderRepository.findById(9L)).thenReturn(Optional.of(folderInB));

        assertThatThrownBy(() -> service.moveRequest(1L, 9L))
                .isInstanceOf(ApiTestingException.class)
                .hasMessageContaining("different collection");
    }

    @Test
    void getCollection_notFound_throws() {
        when(collectionRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getCollection(999L))
                .isInstanceOf(ApiTestingException.class)
                .hasMessageContaining("not found");
    }
}
