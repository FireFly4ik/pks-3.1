package ru.mirea.hospital.service;

import ru.mirea.hospital.model.*;
import ru.mirea.hospital.repository.AppointmentRepository;
import ru.mirea.hospital.exception.*;
import java.time.*;
import java.util.*;

public class AppointmentService {
    private final AppointmentRepository appointments;
    private final UserService users;
    private final Clock clock;
    public AppointmentService(AppointmentRepository appointments, UserService users, Clock clock) {
        this.appointments = appointments; this.users = users; this.clock = clock;
    }
    public List<Appointment> list(User actor) {
        User current = users.requireUser(actor);
        appointments.refreshStatuses(LocalDateTime.now(clock));
        return appointments.list().stream().filter(a -> current.getRole() == Role.ADMIN || a.patientId() == current.getId()).toList();
    }
    public Appointment find(User actor, long id) {
        return list(actor).stream().filter(a -> a.id() == id).findFirst().orElseThrow(() -> new EntityNotFoundException("Запись"));
    }
    public List<Doctor> doctors(User actor) { users.requireUser(actor); return appointments.doctors(); }
    public List<Doctor> availableDoctors(User actor, String specialty, LocalDateTime time, Long excludingId) {
        users.requireUser(actor);
        long patientId = excludingId == null ? actor.getId() : find(actor, excludingId).patientId();
        validateTime(time);
        List<Appointment> all = appointments.list();
        if (all.stream().anyMatch(a -> !Objects.equals(a.id(), excludingId) && a.patientId() == patientId
                && a.startsAt().equals(time) && a.status() != AppointmentStatus.CANCELLED)) return List.of();
        return appointments.doctors().stream().filter(d -> d.specialty().equals(specialty))
                .filter(d -> all.stream().noneMatch(a -> !Objects.equals(a.id(), excludingId) && a.doctorId() == d.id()
                        && a.startsAt().equals(time) && a.status() != AppointmentStatus.CANCELLED)).toList();
    }
    public List<LocalTime> availableTimes(User actor, String specialty, LocalDate day, Long excludingId) {
        validateDate(day);
        List<LocalTime> result = new ArrayList<>();
        for (LocalTime t = LocalTime.of(9, 0); t.isBefore(LocalTime.of(17, 0)); t = t.plusMinutes(30)) {
            if (day.atTime(t).isAfter(LocalDateTime.now(clock)) && !availableDoctors(actor, specialty, day.atTime(t), excludingId).isEmpty()) result.add(t);
        }
        return result;
    }
    public long book(User actor, long doctorId, LocalDateTime time) {
        users.requireUser(actor); validateTime(time);
        if (appointments.doctors().stream().noneMatch(d -> d.id() == doctorId)) throw new EntityNotFoundException("Врач");
        return appointments.book(actor.getId(), doctorId, time, LocalDateTime.now(clock), null);
    }
    public void reschedule(User actor, long id, long doctorId, LocalDateTime time) {
        Appointment old = find(actor, id);
        if (old.status() != AppointmentStatus.UPCOMING) throw new BusinessException("Можно переносить только предстоящую запись.");
        validateTime(time);
        if (old.doctorId() == doctorId && old.startsAt().equals(time)) throw new BusinessException("Выберите другое время или врача.");
        if (appointments.doctors().stream().noneMatch(d -> d.id() == doctorId)) throw new EntityNotFoundException("Врач");
        appointments.book(old.patientId(), doctorId, time, LocalDateTime.now(clock), id);
    }
    public void cancel(User actor, long id) {
        if (find(actor, id).status() != AppointmentStatus.UPCOMING) throw new BusinessException("Можно отменить только предстоящую запись.");
        appointments.cancel(id, LocalDateTime.now(clock));
    }
    public void delete(User actor, long id) {
        Appointment a = find(actor, id);
        if (a.status() != AppointmentStatus.COMPLETED && a.status() != AppointmentStatus.CANCELLED)
            throw new BusinessException("Сначала отмените запись. Приём в процессе удалить нельзя.");
        appointments.delete(id);
    }
    public List<Appointment> search(User actor, String query, boolean bySpecialty) {
        if (query.isBlank()) throw new BusinessException("Поисковый запрос не должен быть пустым.");
        String q = query.strip().toLowerCase(Locale.ROOT);
        return list(actor).stream().filter(a -> (bySpecialty ? a.specialty() : a.doctorName().split("\\s+")[0]).toLowerCase(Locale.ROOT).contains(q)).toList();
    }
    public List<Appointment> filter(User actor, AppointmentStatus status, LocalDate from, LocalDate to) {
        if (from != null && to != null && from.isAfter(to)) throw new BusinessException("Начало диапазона позже окончания.");
        return list(actor).stream().filter(a -> status == null || a.status() == status)
                .filter(a -> from == null || !a.startsAt().toLocalDate().isBefore(from))
                .filter(a -> to == null || !a.startsAt().toLocalDate().isAfter(to)).toList();
    }
    public List<Appointment> sorted(User actor, boolean byCreation, boolean descending) {
        Comparator<Appointment> order = Comparator.comparing(byCreation ? Appointment::createdAt : Appointment::startsAt);
        if (descending) order = order.reversed();
        return list(actor).stream().sorted(order.thenComparingLong(Appointment::id)).toList();
    }
    public Map<String, Long> statistics(User actor) {
        List<Appointment> all = list(actor);
        Map<String, Long> stats = new LinkedHashMap<>();
        if (users.requireUser(actor).getRole() == Role.ADMIN) stats.put("Пользователей", (long) users.list(actor).size());
        stats.put("Всего записей", (long) all.size());
        for (AppointmentStatus status : AppointmentStatus.values())
            stats.put(status.toString(), all.stream().filter(a -> a.status() == status).count());
        stats.put("Активных (предстоит + в процессе)", all.stream().filter(a -> a.status() == AppointmentStatus.UPCOMING || a.status() == AppointmentStatus.IN_PROGRESS).count());
        stats.put("Перенесённых записей", all.stream().filter(a -> a.rescheduleCount() > 0).count());
        stats.put("Всего переносов", all.stream().mapToLong(Appointment::rescheduleCount).sum());
        return stats;
    }
    private void validateDate(LocalDate day) {
        LocalDate today = LocalDate.now(clock);
        if (day.isBefore(today) || day.isAfter(today.plusDays(90))) throw new BusinessException("Запись доступна на ближайшие 90 дней, включая сегодня.");
        if (day.getDayOfWeek() == DayOfWeek.SATURDAY || day.getDayOfWeek() == DayOfWeek.SUNDAY) throw new BusinessException("Клиника работает с понедельника по пятницу.");
    }
    private void validateTime(LocalDateTime time) {
        validateDate(time.toLocalDate());
        if (!time.isAfter(LocalDateTime.now(clock))) throw new BusinessException("Нельзя записаться в прошлое или на уже начавшийся приём.");
        if (time.getHour() < 9 || time.getHour() >= 17 || time.getMinute() % 30 != 0 || time.getSecond() != 0 || time.getNano() != 0)
            throw new BusinessException("Приём: с 09:00 до 16:30, начало каждые 30 минут.");
    }
}
