package ru.mirea.hospital.web.dto;

import java.math.BigDecimal;
import java.util.List;

/** DTO аналитической панели. */
public final class AnalyticsDtos {
    private AnalyticsDtos() {
    }

    public record NamedCount(String name, long count) {
    }

    public record AnalyticsResponse(
            long total,
            long active,
            long archived,
            long withCallback,
            BigDecimal readiness,
            List<NamedCount> byDoctor,
            List<NamedCount> byStatus,
            List<NamedCount> byPriority) {
    }
}
