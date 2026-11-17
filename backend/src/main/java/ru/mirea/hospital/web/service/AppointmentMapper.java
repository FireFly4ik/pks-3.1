package ru.mirea.hospital.web.service;

import org.springframework.stereotype.Component;
import ru.mirea.hospital.web.domain.Appointment;
import ru.mirea.hospital.web.dto.AppointmentDtos.AppointmentResponse;

import java.util.Set;

/** Преобразует entity в публичный DTO внутри активной транзакции. */
@Component
public class AppointmentMapper {
    public AppointmentResponse toResponse(Appointment appointment) {
        return new AppointmentResponse(appointment.getId(), appointment.getExternalId(), appointment.getPatientLogin(),
                appointment.getReason(), appointment.getDetails(), appointment.getEmail(),
                appointment.getPhone(), appointment.getStartsAt(),
                appointment.getDoctor() == null ? null : appointment.getDoctor().getId(),
                appointment.getDoctor() == null ? null : appointment.getDoctor().getCode(),
                appointment.getStatus(), appointment.getReadiness(), appointment.isNeedsCall(), appointment.getPriorityLevel(),
                appointment.isArchived(), appointment.getArchivedAt(), Set.copyOf(appointment.getTags()),
                appointment.getCreatedAt(), appointment.getUpdatedAt(), appointment.getVersion());
    }
}
