package ru.mirea.hospital.web.service;

import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static ru.mirea.hospital.web.domain.AppointmentStatus.*;

class AppointmentStatusPolicyTest {
    @Test void statusFollowsTimeAndPreservesCancellation() {
        var start = LocalDateTime.of(2026, 11, 23, 10, 0);
        assertEquals(UPCOMING, AppointmentStatusPolicy.effective(UPCOMING, start, start.minusSeconds(1)));
        assertEquals(IN_PROGRESS, AppointmentStatusPolicy.effective(UPCOMING, start, start));
        assertEquals(COMPLETED, AppointmentStatusPolicy.effective(UPCOMING, start, start.plusMinutes(30)));
        assertEquals(CANCELLED, AppointmentStatusPolicy.effective(CANCELLED, start, start.plusDays(1)));
    }
}
