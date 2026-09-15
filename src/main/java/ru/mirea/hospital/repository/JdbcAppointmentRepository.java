package ru.mirea.hospital.repository;

import ru.mirea.hospital.model.*;
import ru.mirea.hospital.exception.BusinessException;
import ru.mirea.hospital.util.DatabaseManager;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;

public class JdbcAppointmentRepository implements AppointmentRepository {
    private final DatabaseManager db;
    public JdbcAppointmentRepository(DatabaseManager db) { this.db = db; }
    public List<Appointment> list() {
        String sql = """
                SELECT a.*, u.full_name patient_name, d.full_name doctor_name, d.specialty
                FROM appointments a JOIN users u ON u.id = a.patient_id JOIN doctors d ON d.id = a.doctor_id
                ORDER BY a.id
                """;
        try (var c = db.connect(); var s = c.prepareStatement(sql); var r = s.executeQuery()) {
            List<Appointment> appointments = new ArrayList<>();
            while (r.next()) appointments.add(new Appointment(r.getLong("id"), r.getLong("patient_id"),
                    r.getString("patient_name"), r.getLong("doctor_id"), r.getString("doctor_name"), r.getString("specialty"),
                    r.getObject("starts_at", LocalDateTime.class), r.getObject("created_at", LocalDateTime.class),
                    AppointmentStatus.valueOf(r.getString("status")), r.getInt("reschedule_count")));
            return appointments;
        } catch (SQLException e) { throw DatabaseManager.failure(e); }
    }
    public List<Doctor> doctors() {
        try (var c = db.connect(); var s = c.prepareStatement("SELECT * FROM doctors ORDER BY specialty, full_name"); var r = s.executeQuery()) {
            List<Doctor> doctors = new ArrayList<>();
            while (r.next()) doctors.add(new Doctor(r.getLong("id"), r.getString("full_name"), r.getString("specialty")));
            return doctors;
        } catch (SQLException e) { throw DatabaseManager.failure(e); }
    }
    public void refreshStatuses(LocalDateTime now) {
        String sql = """
                UPDATE appointments SET status = CASE WHEN starts_at + interval '30 minutes' <= ? THEN 'COMPLETED'
                  WHEN starts_at <= ? THEN 'IN_PROGRESS' ELSE 'UPCOMING' END
                WHERE status IN ('UPCOMING', 'IN_PROGRESS')
                """;
        try (var c = db.connect(); var s = c.prepareStatement(sql)) {
            s.setObject(1, now); s.setObject(2, now); s.executeUpdate();
        } catch (SQLException e) { throw DatabaseManager.failure(e); }
    }
    // Блокируем пациента и врача: параллельные сеансы не смогут занять один слот.
    public long book(long patientId, long doctorId, LocalDateTime startsAt, LocalDateTime now, Long appointmentId) {
        try (var c = db.connect()) {
            c.setAutoCommit(false);
            try {
                try (var s = c.prepareStatement("SELECT id FROM users WHERE id = ? AND active FOR UPDATE")) {
                    s.setLong(1, patientId);
                    try (var r = s.executeQuery()) { if (!r.next()) throw new BusinessException("Пациент не найден."); }
                }
                try (var s = c.prepareStatement("SELECT id FROM doctors WHERE id = ? FOR UPDATE")) {
                    s.setLong(1, doctorId);
                    try (var r = s.executeQuery()) { if (!r.next()) throw new BusinessException("Врач не найден."); }
                }
                if (appointmentId != null) {
                    try (var s = c.prepareStatement("SELECT status, starts_at FROM appointments WHERE id = ? AND patient_id = ? FOR UPDATE")) {
                        s.setLong(1, appointmentId); s.setLong(2, patientId);
                        try (var r = s.executeQuery()) {
                            if (!r.next() || !"UPCOMING".equals(r.getString(1)) || !r.getObject(2, LocalDateTime.class).isAfter(now))
                                throw new BusinessException("Можно переносить только предстоящую запись.");
                        }
                    }
                }
                try (var s = c.prepareStatement("""
                        SELECT id FROM appointments WHERE (doctor_id = ? OR patient_id = ?) AND starts_at = ?
                        AND status <> 'CANCELLED' AND id <> ?
                        """)) {
                    s.setLong(1, doctorId); s.setLong(2, patientId); s.setObject(3, startsAt);
                    s.setLong(4, appointmentId == null ? -1 : appointmentId);
                    try (var r = s.executeQuery()) { if (r.next()) throw new BusinessException("Врач или пациент уже занят в это время."); }
                }
                String sql = appointmentId == null
                        ? "INSERT INTO appointments(doctor_id, starts_at, patient_id, created_at, status) VALUES (?, ?, ?, ?, 'UPCOMING') RETURNING id"
                        : "UPDATE appointments SET doctor_id = ?, starts_at = ?, reschedule_count = reschedule_count + 1 WHERE patient_id = ? AND id = ? RETURNING id";
                long id;
                try (var s = c.prepareStatement(sql)) {
                    s.setLong(1, doctorId); s.setObject(2, startsAt); s.setLong(3, patientId);
                    if (appointmentId == null) s.setObject(4, now); else s.setLong(4, appointmentId);
                    try (var r = s.executeQuery()) { r.next(); id = r.getLong(1); }
                }
                c.commit();
                return id;
            } catch (SQLException | RuntimeException e) { c.rollback(); throw e; }
        } catch (SQLException e) { throw DatabaseManager.failure(e); }
    }
    public void cancel(long id, LocalDateTime now) {
        try (var c = db.connect(); var s = c.prepareStatement("UPDATE appointments SET status = 'CANCELLED' WHERE id = ? AND status = 'UPCOMING' AND starts_at > ?")) {
            s.setLong(1, id); s.setObject(2, now);
            if (s.executeUpdate() == 0) throw new BusinessException("Можно отменить только предстоящую запись.");
        } catch (SQLException e) { throw DatabaseManager.failure(e); }
    }
    public void delete(long id) {
        try (var c = db.connect(); var s = c.prepareStatement("DELETE FROM appointments WHERE id = ? AND status IN ('COMPLETED', 'CANCELLED')")) {
            s.setLong(1, id);
            if (s.executeUpdate() == 0) throw new BusinessException("Удалить можно только завершённую или отменённую запись.");
        } catch (SQLException e) { throw DatabaseManager.failure(e); }
    }
}
