package com.miniautomation.backend.apitesting;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniautomation.backend.apitesting.dto.EnvironmentVariable;
import com.miniautomation.backend.entity.ApiEnvironmentEntity;
import com.miniautomation.backend.repository.ApiEnvironmentRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CRUD + variable resolution for API Testing environments (Local / QA /
 * Staging / Production, ...) — the {@code AccessibilityScanService}-style
 * service for this domain: plain CRUD plus the JSON (de)serialisation for
 * its one structured child list (variables).
 */
@Service
public class ApiEnvironmentService {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final ApiEnvironmentRepository repository;

    public ApiEnvironmentService(ApiEnvironmentRepository repository) {
        this.repository = repository;
    }

    public List<ApiEnvironmentEntity> getAll() {
        return repository.findAll();
    }

    public ApiEnvironmentEntity get(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new ApiTestingException("Environment not found: " + id));
    }

    public ApiEnvironmentEntity create(String name, List<EnvironmentVariable> variables) {
        if (name == null || name.trim().isEmpty()) {
            throw new ApiTestingException("Environment name is required.");
        }
        ApiEnvironmentEntity entity = new ApiEnvironmentEntity();
        entity.setName(name.trim());
        entity.setVariablesJson(toJson(variables));
        return repository.save(entity);
    }

    public ApiEnvironmentEntity update(Long id, String name, List<EnvironmentVariable> variables) {
        ApiEnvironmentEntity entity = get(id);
        if (name == null || name.trim().isEmpty()) {
            throw new ApiTestingException("Environment name is required.");
        }
        entity.setName(name.trim());
        entity.setVariablesJson(toJson(variables));
        return repository.save(entity);
    }

    public void delete(Long id) {
        if (!repository.existsById(id)) {
            throw new ApiTestingException("Environment not found: " + id);
        }
        repository.deleteById(id);
    }

    public List<EnvironmentVariable> getVariables(Long id) {
        return parseVariables(get(id).getVariablesJson());
    }

    /** Flat {@code key -> value} map, ready for {@link VariableResolver} — enabled variables only, secrets included (masking happens only at display/persistence time, never at resolution time). */
    public Map<String, String> resolveVariableMap(ApiEnvironmentEntity environment) {
        Map<String, String> map = new LinkedHashMap<>();
        if (environment == null) return map;
        for (EnvironmentVariable v : parseVariables(environment.getVariablesJson())) {
            if (v.getKey() != null && !v.getKey().isBlank()) {
                map.put(v.getKey().trim(), v.getValue() != null ? v.getValue() : "");
            }
        }
        return map;
    }

    /** The resolved value of every variable flagged secret — used to mask those values out of anything persisted/returned from a run. */
    public java.util.Set<String> secretValues(ApiEnvironmentEntity environment) {
        java.util.Set<String> secrets = new java.util.LinkedHashSet<>();
        if (environment == null) return secrets;
        for (EnvironmentVariable v : parseVariables(environment.getVariablesJson())) {
            if (v.isSecret() && v.getValue() != null && !v.getValue().isBlank()) {
                secrets.add(v.getValue());
            }
        }
        return secrets;
    }

    /** Writes an extraction rule's ENVIRONMENT-scoped result back onto the environment — creates the variable if it doesn't already exist, otherwise updates its value in place (preserving its existing secret flag). */
    public void applyVariableUpdates(Long environmentId, Map<String, String> updates) {
        if (updates == null || updates.isEmpty()) return;
        ApiEnvironmentEntity environment = get(environmentId);
        List<EnvironmentVariable> variables = new ArrayList<>(parseVariables(environment.getVariablesJson()));
        for (Map.Entry<String, String> entry : updates.entrySet()) {
            boolean found = false;
            for (EnvironmentVariable v : variables) {
                if (entry.getKey().equals(v.getKey())) {
                    v.setValue(entry.getValue());
                    found = true;
                    break;
                }
            }
            if (!found) {
                variables.add(new EnvironmentVariable(entry.getKey(), entry.getValue(), false));
            }
        }
        environment.setVariablesJson(toJson(variables));
        repository.save(environment);
    }

    private List<EnvironmentVariable> parseVariables(String json) {
        if (json == null || json.isBlank()) return Collections.emptyList();
        try {
            return JSON.readValue(json, new TypeReference<List<EnvironmentVariable>>() { });
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    private String toJson(Object value) {
        try {
            return JSON.writeValueAsString(value != null ? value : Collections.emptyList());
        } catch (Exception e) {
            return "[]";
        }
    }
}
