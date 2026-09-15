package ru.mirea.hospital.repository;

import ru.mirea.hospital.model.*;
import java.time.LocalDateTime;
import java.util.List;

public interface AppointmentRepository {
    List<Appointment> list();
    List<Doctor> doctors();
    void refreshStatuses(LocalDateTime now);
    long book(long patientId, long doctorId, LocalDateTime startsAt, LocalDateTime now, Long appointmentId);
    void cancel(long id, LocalDateTime now);
    void delete(long id);
}
