package ru.mirea.hospital.web.controller;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.mirea.hospital.web.dto.AnalyticsDtos.AnalyticsResponse;
import ru.mirea.hospital.web.service.AnalyticsService;

/** REST endpoint аналитической панели. */
@RestController
@RequestMapping("/api/analytics")
@SecurityRequirement(name = "bearerAuth")
public class AnalyticsController {
    private final AnalyticsService analytics;

    public AnalyticsController(AnalyticsService analytics) { this.analytics = analytics; }

    @GetMapping("/summary")
    @PreAuthorize("hasAnyRole('VIEWER','EDITOR','ADMIN')")
    public AnalyticsResponse summary() { return analytics.summary(); }
}
