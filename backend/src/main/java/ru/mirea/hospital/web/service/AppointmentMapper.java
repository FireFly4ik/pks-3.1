package ru.mirea.hospital.web.service;

import org.springframework.stereotype.Component;
import ru.mirea.hospital.web.domain.Appointment;
import ru.mirea.hospital.web.dto.AppointmentDtos.AppointmentResponse;

import java.util.Set;

/** Преобразует entity в публичный DTO внутри активной транзакции. */
@Component
public class AppointmentMapper {
    private final ru.mirea.hospital.web.repository.UserAccountRepository users;

    public AppointmentMapper(ru.mirea.hospital.web.repository.UserAccountRepository users) {
        this.users = users;
    }

    private final PriorityCalculator priorityCalculator = new PriorityCalculator();

    public AppointmentResponse toResponse(Appointment appointment) {
        var status = AppointmentStatusPolicy.effective(appointment);
        String accountEmail = users.findEmailByPatientLogin(appointment.getPatientLogin()).orElse(null);
        return new AppointmentResponse(appointment.getId(), appointment.getExternalId(), appointment.getPatientLogin(), accountEmail,
                appointment.getReason(), appointment.getDetails(), appointment.getEmail(),
                appointment.getPhone(), appointment.getStartsAt(),
                appointment.getDoctor() == null ? null : appointment.getDoctor().getId(),
                appointment.getDoctor() == null ? null : appointment.getDoctor().getCode(),
                appointment.getDoctor() == null ? null : appointment.getDoctor().getName(),
                status, appointment.getReadiness(), appointment.isNeedsCall(),
                priorityCalculator.calculate(status, appointment.getReadiness(), appointment.isNeedsCall()),
                appointment.isArchived(), appointment.getArchivedAt(), Set.copyOf(appointment.getTags()),
                appointment.getCreatedAt(), appointment.getUpdatedAt(), appointment.getVersion());
    }
}
