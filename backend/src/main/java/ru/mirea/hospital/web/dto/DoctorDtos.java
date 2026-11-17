package ru.mirea.hospital.web.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

/** DTO-контракты врачей. */
public final class DoctorDtos {
    private DoctorDtos() {
    }

    public record DoctorRequest(
            @NotBlank @Size(max = 50) String code,
            @NotBlank @Size(max = 120) String name,
            @NotBlank @Size(max = 80) String specialty,
            Boolean active,
            Long version) {
    }

    public record DoctorResponse(
            UUID id,
            String code,
            String name,
            String specialty,
            boolean active,
            Instant createdAt,
            Instant updatedAt,
            long version) {
    }
}
