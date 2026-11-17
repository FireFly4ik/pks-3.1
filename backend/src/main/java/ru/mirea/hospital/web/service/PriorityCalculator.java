package ru.mirea.hospital.web.service;

import org.springframework.stereotype.Component;
import ru.mirea.hospital.web.domain.PriorityLevel;
import ru.mirea.hospital.web.domain.AppointmentStatus;

import java.math.BigDecimal;

/** Единообразно рассчитывает организационный приоритет для всех клиентов. */
@Component
public class PriorityCalculator {
    public PriorityLevel calculate(AppointmentStatus status, BigDecimal readiness, boolean needsCall) {
        if (status == AppointmentStatus.CANCELLED
                || status == AppointmentStatus.COMPLETED) {
            return PriorityLevel.NOT_APPLICABLE;
        }
        if (needsCall && (readiness == null || readiness.compareTo(new BigDecimal("60.00")) < 0)) return PriorityLevel.CRITICAL;
        if (needsCall || (readiness != null && readiness.compareTo(new BigDecimal("60.00")) < 0)) return PriorityLevel.HIGH;
        if (readiness == null || readiness.compareTo(new BigDecimal("75.00")) < 0) return PriorityLevel.MEDIUM;
        return PriorityLevel.LOW;
    }
}
