package ru.mirea.hospital.web.service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import ru.mirea.hospital.web.domain.Appointment;
import ru.mirea.hospital.web.domain.AppointmentStatus;
import ru.mirea.hospital.web.error.BadRequestException;

/** Общие правила статуса для пациента и сотрудников. */
public final class AppointmentStatusPolicy {
    private AppointmentStatusPolicy() {}
    public static LocalDateTime now() { return LocalDateTime.now(ZoneId.of("Europe/Moscow")); }
    public static AppointmentStatus effective(Appointment a) {
        return effective(a.getStatus(), a.getStartsAt(), now());
    }
    public static AppointmentStatus effective(AppointmentStatus stored, LocalDateTime starts, LocalDateTime now) {
        if (stored == AppointmentStatus.CANCELLED || stored == AppointmentStatus.COMPLETED || starts == null) return stored;
        if (!starts.plusMinutes(30).isAfter(now)) return AppointmentStatus.COMPLETED;
        if (!starts.isAfter(now)) return AppointmentStatus.IN_PROGRESS;
        return AppointmentStatus.UPCOMING;
    }
    public static void validate(Appointment existing, AppointmentStatus requested, LocalDateTime starts) {
        var now = now();
        if (requested == null || starts == null) throw new BadRequestException("Укажите время и статус приёма");
        if (existing != null && existing.getStartsAt() != null) {
            var old = effective(existing.getStatus(), existing.getStartsAt(), now);
            if ((old == AppointmentStatus.CANCELLED || old == AppointmentStatus.COMPLETED)
                    && (requested != old || !starts.equals(existing.getStartsAt())))
                throw new BadRequestException("Завершённую или отменённую запись нельзя возобновить или перенести");
        }
        if (requested == AppointmentStatus.CANCELLED) return;
        if (existing != null && existing.getStatus() == AppointmentStatus.COMPLETED
                && requested == AppointmentStatus.COMPLETED && starts.equals(existing.getStartsAt())) return;
        var expected = effective(AppointmentStatus.UPCOMING, starts, now);
        if (requested != expected) throw new BadRequestException("Статус не соответствует времени приёма");
    }
}
