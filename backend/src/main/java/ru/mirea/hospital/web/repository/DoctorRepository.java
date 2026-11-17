package ru.mirea.hospital.web.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.mirea.hospital.web.domain.Doctor;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persistence gateway for study doctors. */
public interface DoctorRepository extends JpaRepository<Doctor, UUID> {
    Optional<Doctor> findByCodeIgnoreCase(String code);
    boolean existsByCodeIgnoreCase(String code);
    List<Doctor> findAllByOrderByCodeAsc();
    List<Doctor> findByActiveTrueOrderByCodeAsc();
}
