package ru.mirea.hospital.web.filter;

import ru.mirea.hospital.web.domain.PriorityLevel;
import ru.mirea.hospital.web.domain.AppointmentStatus;

import java.math.BigDecimal;
import java.util.List;

/** Результат безопасного разбора строки умного фильтра. */
public record AppointmentFilter(
        String freeText,
        String doctor,
        AppointmentStatus status,
        Boolean callback,
        PriorityLevel priority,
        String tag,
        Boolean archived,
        List<ScoreConstraint> scores) {

    public enum Operator { LT, LTE, EQ, GTE, GT }
    public record ScoreConstraint(Operator operator, BigDecimal value) { }
}
