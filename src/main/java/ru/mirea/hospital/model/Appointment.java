package ru.mirea.hospital.model;

import java.time.LocalDateTime;

public record Appointment(long id, long patientId, String patientName, long doctorId,
                          String doctorName, String specialty, LocalDateTime startsAt,
                          LocalDateTime createdAt, AppointmentStatus status, int rescheduleCount) {}
