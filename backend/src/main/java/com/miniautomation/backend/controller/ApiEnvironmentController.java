package com.miniautomation.backend.controller;

import com.miniautomation.backend.apitesting.ApiEnvironmentService;
import com.miniautomation.backend.apitesting.dto.EnvironmentVariable;
import com.miniautomation.backend.entity.ApiEnvironmentEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** CRUD for API Testing environments (Local / QA / Staging / Production, ...). */
@RestController
@RequestMapping("/api/api-testing/environments")
@CrossOrigin(origins = "*")
public class ApiEnvironmentController {

    private final ApiEnvironmentService service;

    public ApiEnvironmentController(ApiEnvironmentService service) {
        this.service = service;
    }

    @GetMapping
    public List<ApiEnvironmentEntity> getAll() {
        return service.getAll();
    }

    @GetMapping("/{id}")
    public ApiEnvironmentEntity get(@PathVariable Long id) {
        return service.get(id);
    }

    @PostMapping
    public ApiEnvironmentEntity create(@RequestBody SaveEnvironmentRequest request) {
        return service.create(request.getName(), request.getVariables());
    }

    @PutMapping("/{id}")
    public ApiEnvironmentEntity update(@PathVariable Long id, @RequestBody SaveEnvironmentRequest request) {
        return service.update(id, request.getName(), request.getVariables());
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        service.delete(id);
    }

    public static class SaveEnvironmentRequest {
        private String name;
        private List<EnvironmentVariable> variables;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }

        public List<EnvironmentVariable> getVariables() { return variables; }
        public void setVariables(List<EnvironmentVariable> variables) { this.variables = variables; }
    }
}
