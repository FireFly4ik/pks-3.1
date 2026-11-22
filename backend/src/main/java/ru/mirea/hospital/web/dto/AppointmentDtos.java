package ru.mirea.hospital.web.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import ru.mirea.hospital.web.domain.PriorityLevel;
import ru.mirea.hospital.web.domain.AppointmentStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** DTO-контракты приёмов и массовых операций. */
public final class AppointmentDtos {
    private AppointmentDtos() {
    }

    /** Входная модель создания и изменения карточки. */
    public record AppointmentRequest(
            @NotBlank @Size(max = 100) String externalId,
            @NotBlank @Size(max = 100) String patientLogin,
            @NotBlank @Size(max = 100) String reason,
            @Size(max = 100) String details,
            @Email @Size(max = 254) String email,
            @Pattern(regexp = "^\\+7-\\d{3}-\\d{3}-\\d{2}-\\d{2}$", message = "некорректный телефон") String phone,
            @NotNull LocalDateTime startsAt,
            @NotNull UUID doctorId,
            @NotNull AppointmentStatus status,
            @DecimalMin("0.00") @DecimalMax("100.00") BigDecimal readiness,
            boolean needsCall,
            @Size(max = 20) Set<@NotBlank @Size(max = 50) String> tags,
            Long version) {
    }

    /** Выходная карточка приёма. */
    public record AppointmentResponse(
            UUID id,
            String externalId,
            String patientLogin,
            String patientAccountEmail,
            String reason,
            String details,
            String email,
            String phone,
            LocalDateTime startsAt,
            @NotNull UUID doctorId,
            String doctorCode,
            String doctorName,
            AppointmentStatus status,
            BigDecimal readiness,
            boolean needsCall,
            PriorityLevel priorityLevel,
            boolean archived,
            Instant archivedAt,
            Set<String> tags,
            Instant createdAt,
            Instant updatedAt,
            long version) {
    }

    /** Поддерживаемые массовые действия. */
    public enum BulkAction { ARCHIVE, RESTORE, SET_STATUS, ADD_TAG, REMOVE_TAG }

    /** Запрос массового изменения. */
    public record BulkRequest(
            @NotNull @Size(min = 1, max = 500) Set<UUID> ids,
            @NotNull BulkAction action,
            AppointmentStatus status,
            @Size(max = 50) String tag) {
    }

    /** Результат массовой операции с явным списком ошибок. */
    public record BulkResult(int requested, int succeeded, List<BulkFailure> failures) {
    }

    /** Ошибка отдельной записи в массовой операции. */
    public record BulkFailure(UUID id, String message) {
    }
}
