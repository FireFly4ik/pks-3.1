package ru.mirea.hospital.web.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import ru.mirea.hospital.web.domain.Appointment;
import ru.mirea.hospital.web.domain.AppointmentStatus;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persistence gateway for appointments, smart specifications and compact analytics. */
public interface AppointmentRepository extends JpaRepository<Appointment, UUID>, JpaSpecificationExecutor<Appointment> {
    @Query(value = "select exists(select 1 from users where login = :login and active)", nativeQuery = true)
    boolean activePatient(String login);

    @Query("select count(a) > 0 from Appointment a where a.id <> :excluded and a.startsAt = :startsAt " +
            "and a.status <> ru.mirea.hospital.web.domain.AppointmentStatus.CANCELLED " +
            "and (a.doctor.id = :doctor or a.patientLogin = :login)")
    boolean occupied(UUID excluded, java.time.LocalDateTime startsAt, UUID doctor, String login);

    @Query(value = "select login from users where web_account_id = :account and active", nativeQuery = true)
    Optional<String> patientLogin(UUID account);

    List<Appointment> findByPatientLoginOrderByStartsAtDesc(String login);

    @Query("select a.startsAt from Appointment a where a.startsAt >= :start and a.startsAt < :end " +
            "and a.status <> ru.mirea.hospital.web.domain.AppointmentStatus.CANCELLED " +
            "and (a.doctor.id = :doctor or a.patientLogin = :login)")
    List<java.time.LocalDateTime> busy(UUID doctor, String login, java.time.LocalDateTime start, java.time.LocalDateTime end);

    boolean existsByExternalIdIgnoreCase(String externalId);
    boolean existsByExternalIdIgnoreCaseAndIdNot(String externalId, UUID id);
    Optional<Appointment> findByExternalIdIgnoreCase(String externalId);
    long countByArchivedFalse();
    long countByArchivedTrue();
    long countByArchivedFalseAndNeedsCallTrue();
    long countByArchivedFalseAndStatus(AppointmentStatus status);

    @Query("select coalesce(avg(s.readiness), 0) from Appointment s where s.archived = false and s.readiness is not null")
    BigDecimal readinessForActive();

    @Query("select coalesce(g.code, 'Без врача'), count(s) from Appointment s left join s.doctor g " +
           "where s.archived = false group by g.code order by count(s) desc")
    List<Object[]> countActiveByDoctor();

    @Query("select s.status, count(s) from Appointment s where s.archived = false group by s.status order by count(s) desc")
    List<Object[]> countActiveByStatus();

    @Query("select s.priorityLevel, count(s) from Appointment s where s.archived = false group by s.priorityLevel order by count(s) desc")
    List<Object[]> countActiveByPriority();
}
