package com.miniautomation.backend.apitesting;

import com.miniautomation.backend.apitesting.dto.EnvironmentVariable;
import com.miniautomation.backend.entity.ApiEnvironmentEntity;
import com.miniautomation.backend.repository.ApiEnvironmentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ApiEnvironmentServiceTest {

    private ApiEnvironmentRepository repository;
    private ApiEnvironmentService service;

    @BeforeEach
    void setUp() {
        repository = mock(ApiEnvironmentRepository.class);
        service = new ApiEnvironmentService(repository);
        when(repository.save(any(ApiEnvironmentEntity.class))).thenAnswer(inv -> {
            ApiEnvironmentEntity e = inv.getArgument(0);
            if (e.getId() == null) e.setId(1L);
            return e;
        });
    }

    @Test
    void create_rejectsBlankName() {
        assertThatThrownBy(() -> service.create("  ", List.of()))
                .isInstanceOf(ApiTestingException.class)
                .hasMessageContaining("name is required");
    }

    @Test
    void resolveVariableMap_returnsFlatKeyValueMapIncludingSecrets() {
        ApiEnvironmentEntity env = service.create("Local", List.of(
                new EnvironmentVariable("baseUrl", "http://localhost:8080", false),
                new EnvironmentVariable("token", "sk_live_abc123", true)));

        Map<String, String> resolved = service.resolveVariableMap(env);

        assertThat(resolved).containsEntry("baseUrl", "http://localhost:8080");
        assertThat(resolved).containsEntry("token", "sk_live_abc123");
    }

    @Test
    void secretValues_returnsOnlyValuesFlaggedSecret() {
        ApiEnvironmentEntity env = service.create("Local", List.of(
                new EnvironmentVariable("baseUrl", "http://localhost:8080", false),
                new EnvironmentVariable("token", "sk_live_abc123", true)));

        Set<String> secrets = service.secretValues(env);

        assertThat(secrets).containsExactly("sk_live_abc123");
        assertThat(secrets).doesNotContain("http://localhost:8080");
    }

    @Test
    void applyVariableUpdates_updatesExistingVariableInPlace_preservingOtherEntries() {
        ApiEnvironmentEntity env = service.create("Local", List.of(
                new EnvironmentVariable("baseUrl", "http://localhost:8080", false),
                new EnvironmentVariable("token", "old-token", false)));
        when(repository.findById(1L)).thenReturn(Optional.of(env));

        service.applyVariableUpdates(1L, Map.of("token", "new-token-from-login"));

        Map<String, String> resolved = service.resolveVariableMap(env);
        assertThat(resolved).containsEntry("token", "new-token-from-login");
        assertThat(resolved).containsEntry("baseUrl", "http://localhost:8080");
    }

    @Test
    void applyVariableUpdates_createsNewVariableWhenNotAlreadyPresent() {
        ApiEnvironmentEntity env = service.create("Local", List.of());
        when(repository.findById(1L)).thenReturn(Optional.of(env));

        service.applyVariableUpdates(1L, Map.of("userId", "42"));

        assertThat(service.resolveVariableMap(env)).containsEntry("userId", "42");
    }

    @Test
    void delete_unknownId_throws() {
        when(repository.existsById(999L)).thenReturn(false);

        assertThatThrownBy(() -> service.delete(999L))
                .isInstanceOf(ApiTestingException.class);
    }
}
