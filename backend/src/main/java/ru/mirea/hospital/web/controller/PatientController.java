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

    public PatientController(AppointmentRepository visits, CurrentUserService current,
                             AppointmentService appointments, DoctorService doctors) {
        this.visits = visits;
        this.current = current;
        this.appointments = appointments;
        this.doctors = doctors;
    }

    public record Booking(@NotNull UUID doctorId, @NotNull LocalDateTime startsAt,
                          @NotBlank @Size(max = 100) String reason) {}
    public record Visit(UUID id, LocalDateTime startsAt, String doctor, String specialty,
                        String reason, AppointmentStatus status) {}

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
    public List<LocalDateTime> slots(@RequestParam UUID doctorId, @RequestParam LocalDate date) {
        LocalDateTime now = LocalDateTime.now(ZoneId.of("Europe/Moscow"));
        if (date.isBefore(now.toLocalDate()) || date.isAfter(now.toLocalDate().plusDays(90))
                || date.getDayOfWeek().getValue() > 5) return List.of();

        if (doctors.list(true).stream().noneMatch(doctor -> doctor.id().equals(doctorId))) return List.of();

        var busy = visits.busy(doctorId, patientLogin(), date.atTime(9, 0), date.atTime(17, 0));
        List<LocalDateTime> result = new ArrayList<>();
        for (var slot = date.atTime(9, 0); slot.isBefore(date.atTime(17, 0)); slot = slot.plusMinutes(30)) {
            if (slot.isAfter(now) && !busy.contains(slot)) result.add(slot);
        }
        return result;
    }

    @GetMapping("/appointments")
    @Transactional(readOnly = true)
    public List<Visit> list() {
        return visits.findByPatientLoginOrderByStartsAtDesc(patientLogin()).stream().map(a ->
                new Visit(a.getId(), a.getStartsAt(), a.getDoctor().getName(), a.getDoctor().getSpecialty(),
                        a.getReason(), a.getStatus())).toList();
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
}
