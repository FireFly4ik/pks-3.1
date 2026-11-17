package ru.mirea.hospital.web.domain;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Основная доменная сущность приёма.
 *
 * <p>Обычное удаление реализовано через архивирование, поэтому история и связанные
 * записи остаются доступными для восстановления.</p>
 */
@Entity
@Table(name = "appointments")
@jakarta.persistence.AttributeOverride(name="createdAt", column=@Column(name="web_created_at", nullable=false, updatable=false))
public class Appointment extends AuditedVersionedEntity {
    @Id
    @Column(name="public_id")
    private UUID id;

    @Column(name = "external_id", unique = true, length = 100)
    private String externalId;

    @Column(name = "patient_login", nullable = false, length = 100)
    private String patientLogin;

    @Column(name = "reason", nullable = false, length = 100)
    private String reason;

    @Column(name = "details", length = 100)
    private String details;

    @Column(length = 254)
    private String email;

    @Column(length = 32)
    private String phone;

    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.LOCAL_DATE_TIME)
    @Column(name = "starts_at")
    private LocalDateTime startsAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "doctor_uuid")
    private Doctor doctor;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private AppointmentStatus status = AppointmentStatus.UPCOMING;

    @Column(name = "readiness", precision = 5, scale = 2)
    private BigDecimal readiness;

    @Column(name = "needs_call", nullable = false)
    private boolean needsCall;

    @Enumerated(EnumType.STRING)
    @Column(name = "priority_level", nullable = false, length = 32)
    private PriorityLevel priorityLevel = PriorityLevel.LOW;

    @Column(nullable = false)
    private boolean archived;

    @Column(name = "archived_at")
    private Instant archivedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "archived_by")
    private UserAccount archivedBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by")
    private UserAccount createdBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "updated_by")
    private UserAccount updatedBy;

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "appointment_tag", joinColumns = @JoinColumn(name = "appointment_id"))
    @Column(name = "tag", nullable = false, length = 50)
    private Set<String> tags = new LinkedHashSet<>();

    protected Appointment() {
    }

    public Appointment(UUID id, String patientLogin, String reason, UserAccount creator) {
        this.id = id;
        this.patientLogin = patientLogin;
        this.reason = reason;
        this.createdBy = creator;
        this.updatedBy = creator;
    }

    public UUID getId() { return id; }
    public String getExternalId() { return externalId; }
    public void setExternalId(String externalId) { this.externalId = externalId; }
    public String getPatientLogin() { return patientLogin; }
    public void setPatientLogin(String patientLogin) { this.patientLogin = patientLogin; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public String getDetails() { return details; }
    public void setDetails(String details) { this.details = details; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
    public LocalDateTime getStartsAt() { return startsAt; }
    public void setStartsAt(LocalDateTime startsAt) { this.startsAt = startsAt; }
    public Doctor getDoctor() { return doctor; }
    public void setDoctor(Doctor doctor) { this.doctor = doctor; }
    public AppointmentStatus getStatus() { return status; }
    public void setStatus(AppointmentStatus status) { this.status = status; }
    public BigDecimal getReadiness() { return readiness; }
    public void setReadiness(BigDecimal readiness) { this.readiness = readiness; }
    public boolean isNeedsCall() { return needsCall; }
    public void setNeedsCall(boolean needsCall) { this.needsCall = needsCall; }
    public PriorityLevel getPriorityLevel() { return priorityLevel; }
    public void setPriorityLevel(PriorityLevel priorityLevel) { this.priorityLevel = priorityLevel; }
    public boolean isArchived() { return archived; }
    public void setArchived(boolean archived) { this.archived = archived; }
    public Instant getArchivedAt() { return archivedAt; }
    public void setArchivedAt(Instant archivedAt) { this.archivedAt = archivedAt; }
    public UserAccount getArchivedBy() { return archivedBy; }
    public void setArchivedBy(UserAccount archivedBy) { this.archivedBy = archivedBy; }
    public UserAccount getCreatedBy() { return createdBy; }
    public UserAccount getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(UserAccount updatedBy) { this.updatedBy = updatedBy; }
    public Set<String> getTags() { return tags; }
    public void setTags(Set<String> tags) { this.tags = new LinkedHashSet<>(tags); }
}
