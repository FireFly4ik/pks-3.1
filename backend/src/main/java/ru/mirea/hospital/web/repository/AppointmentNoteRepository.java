package ru.mirea.hospital.web.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.mirea.hospital.web.domain.AppointmentNote;

import java.util.List;
import java.util.UUID;

/** Persistence gateway for appointment notes. */
public interface AppointmentNoteRepository extends JpaRepository<AppointmentNote, UUID> {
    List<AppointmentNote> findByAppointmentIdOrderByPinnedDescCreatedAtDesc(UUID appointmentId);
}
