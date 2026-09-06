package com.miniautomation.backend.controller;

import com.miniautomation.backend.service.DashboardService;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only aggregation endpoints spanning ALL tests/runs — backs the global
 * Dashboard stats/activity feed and the global Execution History page.
 * Purely additive: no existing endpoint, entity, or service is touched.
 */
@RestController
@RequestMapping("/api/ui-automation")
@CrossOrigin(origins = "*")
public class DashboardController {

    private final DashboardService dashboardService;

    public DashboardController(DashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    @GetMapping("/dashboard/summary")
    public DashboardService.DashboardSummary getSummary() {
        return dashboardService.getSummary();
    }

    @GetMapping("/dashboard/runs")
    public DashboardService.DashboardRuns getAllRuns() {
        return dashboardService.getAllRuns();
    }
}
