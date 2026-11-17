package ru.mirea.hospital.web.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.mirea.hospital.web.domain.Doctor;
import ru.mirea.hospital.web.dto.DoctorDtos.DoctorRequest;
import ru.mirea.hospital.web.dto.DoctorDtos.DoctorResponse;
import ru.mirea.hospital.web.error.BadRequestException;
import ru.mirea.hospital.web.error.ConflictException;
import ru.mirea.hospital.web.error.NotFoundException;
import ru.mirea.hospital.web.repository.DoctorRepository;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Прикладные операции со справочником врачей. */
@Service
public class DoctorService {
    private final DoctorRepository doctors;
    private final AuditService audit;

    public DoctorService(DoctorRepository doctors, AuditService audit) {
        this.doctors = doctors;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<DoctorResponse> list(boolean activeOnly) {
        return (activeOnly ? doctors.findByActiveTrueOrderByCodeAsc() : doctors.findAllByOrderByCodeAsc())
                .stream().map(DoctorService::toResponse).toList();
    }

    @Transactional
    public DoctorResponse create(DoctorRequest request) {
        String code = normalizeCode(request.code());
        if (doctors.existsByCodeIgnoreCase(code)) throw new ConflictException("Врач с таким кодом уже существует");
        Doctor doctor = new Doctor(UUID.randomUUID(), code, normalizeNullable(request.name()), request.specialty());
        if (request.active() != null) doctor.setActive(request.active());
        doctor = doctors.saveAndFlush(doctor);
        audit.record("DOCTOR_CREATED", "DOCTOR", doctor.getId(), "Добавлен врач " + code, Map.of());
        doctors.flush();
        return toResponse(doctor);
    }

    @Transactional
    public DoctorResponse update(UUID id, DoctorRequest request) {
        Doctor doctor = require(id);
        if (request.version() == null || request.version() != doctor.getVersion())
            throw new ConflictException("Данные врача уже изменены. Обновите список");
        String code = normalizeCode(request.code());
        doctors.findByCodeIgnoreCase(code).filter(other -> !other.getId().equals(id)).ifPresent(other -> {
            throw new ConflictException("Врач с таким кодом уже существует");
        });
        doctor.setCode(code);
        doctor.setName(normalizeNullable(request.name()));
        doctor.setSpecialty(request.specialty());
        if (request.active() != null) doctor.setActive(request.active());
        audit.record("DOCTOR_UPDATED", "DOCTOR", id, "Обновлены данные врача " + code, Map.of());
        doctors.flush();
        return toResponse(doctor);
    }

    @Transactional
    public DoctorResponse setActive(UUID id, boolean active, long version) {
        Doctor doctor = require(id);
        if (version != doctor.getVersion()) throw new ConflictException("Данные врача уже изменены. Обновите список");
        doctor.setActive(active);
        audit.record(active ? "DOCTOR_ACTIVATED" : "DOCTOR_DEACTIVATED", "DOCTOR", id,
                (active ? "Активирован" : "Деактивирован") + " врач " + doctor.getCode(), Map.of());
        doctors.flush();
        return toResponse(doctor);
    }

    private Doctor require(UUID id) {
        return doctors.findById(id).orElseThrow(() -> new NotFoundException("Врач не найден"));
    }

    private static DoctorResponse toResponse(Doctor doctor) {
        return new DoctorResponse(doctor.getId(), doctor.getCode(), doctor.getName(), doctor.getSpecialty(),
                doctor.isActive(), doctor.getCreatedAt(), doctor.getUpdatedAt(), doctor.getVersion());
    }

    private static String normalizeCode(String value) {
        if (value == null || value.isBlank()) throw new BadRequestException("Код врача обязателен");
        return value.trim().replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
    }

    private static String normalizeNullable(String value) {
        return value == null || value.isBlank() ? null : value.trim().replaceAll("\\s+", " ");
    }
}
