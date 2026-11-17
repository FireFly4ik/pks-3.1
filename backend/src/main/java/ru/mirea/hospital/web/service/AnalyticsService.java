package ru.mirea.hospital.web.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.mirea.hospital.web.dto.AnalyticsDtos.AnalyticsResponse;
import ru.mirea.hospital.web.dto.AnalyticsDtos.NamedCount;
import ru.mirea.hospital.web.repository.AppointmentRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/** Формирует компактную аналитическую сводку на стороне базы. */
@Service
public class AnalyticsService {
    private final AppointmentRepository appointments;

    public AnalyticsService(AppointmentRepository appointments) {
        this.appointments = appointments;
    }

    @Transactional(readOnly = true)
    public AnalyticsResponse summary() {
        long total = appointments.count();
        long active = appointments.countByArchivedFalse();
        long archived = appointments.countByArchivedTrue();
        long withCallback = appointments.countByArchivedFalseAndNeedsCallTrue();
        BigDecimal average = appointments.readinessForActive();
        if (average != null) average = average.setScale(2, RoundingMode.HALF_UP);
        return new AnalyticsResponse(total, active, archived, withCallback, average,
                map(appointments.countActiveByDoctor()), map(appointments.countActiveByStatus()), map(appointments.countActiveByPriority()));
    }

    private static List<NamedCount> map(List<Object[]> rows) {
        return rows.stream().map(row -> new NamedCount(String.valueOf(row[0]), ((Number) row[1]).longValue())).toList();
    }
}
