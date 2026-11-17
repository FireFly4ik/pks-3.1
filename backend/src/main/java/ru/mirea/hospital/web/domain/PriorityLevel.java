package ru.mirea.hospital.web.domain;

/** Рассчитанный уровень организационного приоритета. */
public enum PriorityLevel {
    /** Приоритет не рассчитывается для завершённого или отменённого приёма. */
    NOT_APPLICABLE,
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL
}
