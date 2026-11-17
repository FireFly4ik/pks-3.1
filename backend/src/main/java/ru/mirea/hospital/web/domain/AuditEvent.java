package ru.mirea.hospital.web.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Неизменяемая запись аудита.
 *
 * <p>В {@code detailsJson} не записываются пароли, access/refresh-токены и полные
 * CSV-строки, чтобы журнал не содержал лишних чувствительных данных.</p>
 */
@Entity
@Table(name = "audit_event")
public class AuditEvent {
    @Id
    private UUID id;
    @Column(name = "actor_id") private UUID actorId;
    @Column(name = "actor_email", length = 254) private String actorEmail;
    @Column(name = "event_type", nullable = false, length = 80) private String eventType;
    @Column(name = "entity_type", length = 80) private String entityType;
    @Column(name = "entity_id") private UUID entityId;
    @Column(nullable = false, length = 1000) private String summary;
    @Column(name = "correlation_id", length = 100) private String correlationId;
    @Column(name = "ip_address", length = 64) private String ipAddress;
    @Column(name = "user_agent", length = 500) private String userAgent;
    @Column(name = "occurred_at", nullable = false) private Instant occurredAt;
    @Column(name = "details_json", columnDefinition = "TEXT") private String detailsJson;

    protected AuditEvent() {
    }

    public AuditEvent(UUID id, UUID actorId, String actorEmail, String eventType,
                      String entityType, UUID entityId, String summary,
                      String correlationId, String ipAddress, String userAgent,
                      String detailsJson) {
        this.id = id;
        this.actorId = actorId;
        this.actorEmail = actorEmail;
        this.eventType = eventType;
        this.entityType = entityType;
        this.entityId = entityId;
        this.summary = summary;
        this.correlationId = correlationId;
        this.ipAddress = ipAddress;
        this.userAgent = userAgent;
        this.occurredAt = Instant.now();
        this.detailsJson = detailsJson;
    }

    public UUID getId() { return id; }
    public UUID getActorId() { return actorId; }
    public String getActorEmail() { return actorEmail; }
    public String getEventType() { return eventType; }
    public String getEntityType() { return entityType; }
    public UUID getEntityId() { return entityId; }
    public String getSummary() { return summary; }
    public String getCorrelationId() { return correlationId; }
    public String getIpAddress() { return ipAddress; }
    public String getUserAgent() { return userAgent; }
    public Instant getOccurredAt() { return occurredAt; }
    public String getDetailsJson() { return detailsJson; }
}
