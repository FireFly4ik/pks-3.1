package ru.mirea.hospital.web.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import ru.mirea.hospital.web.domain.AppointmentStatus;
import ru.mirea.hospital.web.domain.Appointment;
import ru.mirea.hospital.web.domain.PriorityLevel;
import ru.mirea.hospital.web.error.ConflictException;
import ru.mirea.hospital.web.error.NotFoundException;
import ru.mirea.hospital.web.service.AuditService;
import java.time.Instant;
import java.util.Map;
import ru.mirea.hospital.web.dto.AppointmentDtos.AppointmentRequest;
import ru.mirea.hospital.web.dto.DoctorDtos.DoctorResponse;
import ru.mirea.hospital.web.error.BadRequestException;
import ru.mirea.hospital.web.repository.AppointmentRepository;
import ru.mirea.hospital.web.security.CurrentUserService;
import ru.mirea.hospital.web.service.AppointmentService;
import ru.mirea.hospital.web.service.DoctorService;

@RestController
@RequestMapping("/api/patient")
@PreAuthorize("hasRole('PATIENT')")
public class PatientController {
    private final AppointmentRepository visits;
    private final CurrentUserService current;
    private final AppointmentService appointments;
    private final DoctorService doctors;
    private final AuditService audit;

    public PatientController(AppointmentRepository visits, CurrentUserService current,
                             AppointmentService appointments, DoctorService doctors, AuditService audit) {
        this.audit = audit;
        this.visits = visits;
        this.current = current;
        this.appointments = appointments;
        this.doctors = doctors;
    }

    public record Booking(@NotNull UUID doctorId, @NotNull LocalDateTime startsAt,
                          @NotBlank @Size(max = 100) String reason) {}
    public record Visit(UUID id, LocalDateTime startsAt, String doctor, String specialty,
                        String reason, AppointmentStatus status, UUID doctorId, Instant createdAt,
                        int rescheduleCount, long version) {}

    private String patientLogin() {
        return visits.patientLogin(current.requirePrincipal().id())
                .orElseThrow(() -> new BadRequestException("Профиль пациента недоступен"));
    }

    @GetMapping("/doctors")
    public List<DoctorResponse> doctors() {
        return doctors.list(true);
    }

    @GetMapping("/slots")
    @Transactional(readOnly = true)
    public List<LocalDateTime> slots(@RequestParam UUID doctorId, @RequestParam LocalDate date,
                                     @RequestParam(required = false) UUID moving) {
        LocalDateTime now = LocalDateTime.now(ZoneId.of("Europe/Moscow"));
        if (date.isBefore(now.toLocalDate()) || date.isAfter(now.toLocalDate().plusDays(90))
                || date.getDayOfWeek().getValue() > 5) return List.of();

        if (doctors.list(true).stream().noneMatch(doctor -> doctor.id().equals(doctorId))) return List.of();

        String login = patientLogin();
        UUID excluded = moving == null ? new UUID(0, 0) : own(moving, login).getId();
        var busy = visits.busy(excluded, doctorId, login, date.atTime(9, 0), date.atTime(17, 0));
        List<LocalDateTime> result = new ArrayList<>();
        for (var slot = date.atTime(9, 0); slot.isBefore(date.atTime(17, 0)); slot = slot.plusMinutes(30)) {
            if (slot.isAfter(now) && !busy.contains(slot)) result.add(slot);
        }
        return result;
    }

    @GetMapping("/appointments")
    @Transactional(readOnly = true)
    public List<Visit> list() {
        return visits.findByPatientLoginAndPatientHiddenFalseOrderByStartsAtDesc(patientLogin()).stream().map(a ->
                new Visit(a.getId(), a.getStartsAt(), a.getDoctor().getName(), a.getDoctor().getSpecialty(),
                        a.getReason(), effectiveStatus(a), a.getDoctor().getId(), a.getCreatedAt(),
                        a.getRescheduleCount(), a.getVersion())).toList();
    }

    @PostMapping("/appointments")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public UUID book(@Valid @RequestBody Booking request) {
        String login = patientLogin();
        return appointments.create(new AppointmentRequest("WEB-" + UUID.randomUUID(), login, request.reason(),
                null, null, null, request.startsAt(), request.doctorId(), AppointmentStatus.UPCOMING,
                null, false, Set.of(), null)).id();
    }

    private Appointment own(UUID id, String login) {
        return visits.findByIdAndPatientLoginAndPatientHiddenFalse(id, login)
                .orElseThrow(() -> new NotFoundException("Запись не найдена"));
    }

    private static AppointmentStatus effectiveStatus(Appointment a) {
        if (a.getStatus() == AppointmentStatus.CANCELLED || a.getStatus() == AppointmentStatus.COMPLETED) {
            return a.getStatus();
        }
        var now = LocalDateTime.now(ZoneId.of("Europe/Moscow"));
        if (!a.getStartsAt().plusMinutes(30).isAfter(now)) return AppointmentStatus.COMPLETED;
        if (!a.getStartsAt().isAfter(now)) return AppointmentStatus.IN_PROGRESS;
        return a.getStatus();
    }

    private Appointment upcoming(UUID id, long version) {
        var appointment = own(id, patientLogin());
        if (appointment.getVersion() != version) throw new ConflictException("Запись изменилась. Обновите список.");
        if (effectiveStatus(appointment) != AppointmentStatus.UPCOMING) {
            throw new ConflictException("Изменить можно только предстоящую запись");
        }
        return appointment;
    }

    @PutMapping("/appointments/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void move(@PathVariable UUID id, @RequestParam long version, @Valid @RequestBody Booking request) {
        var a = upcoming(id, version);
        if (a.getDoctor().getId().equals(request.doctorId()) && a.getStartsAt().equals(request.startsAt())) {
            throw new BadRequestException("Выберите другое время или врача");
        }
        appointments.update(id, new AppointmentRequest(a.getExternalId(), a.getPatientLogin(), request.reason(),
                a.getDetails(), a.getEmail(), a.getPhone(), request.startsAt(), request.doctorId(), a.getStatus(),
                a.getReadiness(), a.isNeedsCall(), Set.copyOf(a.getTags()), version));
    }

    @PostMapping("/appointments/{id}/cancel")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void cancel(@PathVariable UUID id, @RequestParam long version) {
        var a = upcoming(id, version);
        a.setStatus(AppointmentStatus.CANCELLED);
        a.setPriorityLevel(PriorityLevel.NOT_APPLICABLE);
        a.setUpdatedBy(current.requireEntity());
        visits.flush();
        audit.record("PATIENT_CANCELLED", "APPOINTMENT", id, "Пациент отменил запись", Map.of());
    }

    @DeleteMapping("/appointments/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void hide(@PathVariable UUID id, @RequestParam long version) {
        var a = own(id, patientLogin());
        if (a.getVersion() != version) throw new ConflictException("Запись изменилась. Обновите список.");
        var status = effectiveStatus(a);
        if (status != AppointmentStatus.COMPLETED && status != AppointmentStatus.CANCELLED) {
            throw new ConflictException("Можно убрать только завершённую или отменённую запись");
        }
        a.hideFromPatient();
        a.setUpdatedBy(current.requireEntity());
        visits.flush();
        audit.record("PATIENT_HID_VISIT", "APPOINTMENT", id, "Пациент убрал запись из своей истории", Map.of());
    }
}
