package ru.mirea.hospital.web.service;

import org.junit.jupiter.api.Test;
import ru.mirea.hospital.web.domain.PriorityLevel;
import ru.mirea.hospital.web.domain.AppointmentStatus;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/** Фиксирует бизнес-правила организационного приоритета. */
class PriorityCalculatorTest {
    private final PriorityCalculator calculator = new PriorityCalculator();

    @Test
    void callbackAndLowScoreAreCritical() {
        assertThat(calculator.calculate(AppointmentStatus.UPCOMING, new BigDecimal("2.90"), true))
                .isEqualTo(PriorityLevel.CRITICAL);
    }

    @Test
    void goodScoreWithoutCallbackIsLow() {
        assertThat(calculator.calculate(AppointmentStatus.UPCOMING, new BigDecimal("80"), false))
                .isEqualTo(PriorityLevel.LOW);
    }

    @Test
    void completedAppointmentIsNotAcademicPriority() {
        assertThat(calculator.calculate(AppointmentStatus.COMPLETED, new BigDecimal("2.10"), true))
                .isEqualTo(PriorityLevel.NOT_APPLICABLE);
    }
}
