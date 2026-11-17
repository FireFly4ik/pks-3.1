package ru.mirea.hospital.web.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ru.mirea.hospital.web.domain.AuditEvent;
import ru.mirea.hospital.web.dto.AuditDtos.AuditResponse;
import ru.mirea.hospital.web.repository.AuditEventRepository;
import ru.mirea.hospital.web.security.UserPrincipal;
import ru.mirea.hospital.web.web.RequestMetadata;

import java.util.Map;
import java.util.UUID;

/** Создаёт и читает журнал значимых действий. */
@Service
public class AuditService {
    private final AuditEventRepository events;
    private final ObjectMapper objectMapper;
    private final RequestMetadata metadata;

    public AuditService(AuditEventRepository events, ObjectMapper objectMapper, RequestMetadata metadata) {
        this.events = events;
        this.objectMapper = objectMapper;
        this.metadata = metadata;
    }

    /**
     * Записывает событие, предварительно сериализуя только явно переданную безопасную карту.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String eventType, String entityType, UUID entityId,
                       String summary, Map<String, ?> safeDetails) {
        UserPrincipal principal = currentPrincipal();
        String json = null;
        if (safeDetails != null && !safeDetails.isEmpty()) {
            try {
                json = objectMapper.writeValueAsString(safeDetails);
            } catch (JsonProcessingException ignored) {
                json = "{\"serialization\":\"failed\"}";
            }
        }
        events.save(new AuditEvent(UUID.randomUUID(),
                principal == null ? null : principal.id(),
                principal == null ? null : principal.email(),
                eventType, entityType, entityId, summary,
                metadata.correlationId(), metadata.ipAddress(), metadata.userAgent(), json));
    }

    /** Ищет записи аудита по разрешённым полям. */
    @Transactional(readOnly = true)
    public Page<AuditResponse> search(String query, Pageable pageable) {
        String normalized = query == null ? "" : query.trim().toLowerCase();
        Specification<AuditEvent> spec = (root, cq, cb) -> {
            if (normalized.isBlank()) return cb.conjunction();
            String like = "%" + normalized + "%";
            return cb.or(
                    cb.like(cb.lower(root.get("actorEmail")), like),
                    cb.like(cb.lower(root.get("eventType")), like),
                    cb.like(cb.lower(root.get("entityType")), like),
                    cb.like(cb.lower(root.get("summary")), like),
                    cb.like(cb.lower(root.get("correlationId")), like));
        };
        return events.findAll(spec, pageable).map(AuditService::toResponse);
    }

    private static AuditResponse toResponse(AuditEvent event) {
        return new AuditResponse(event.getId(), event.getActorId(), event.getActorEmail(),
                event.getEventType(), event.getEntityType(), event.getEntityId(), event.getSummary(),
                event.getCorrelationId(), event.getIpAddress(), event.getOccurredAt(), event.getDetailsJson());
    }

    private static UserPrincipal currentPrincipal() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getPrincipal() instanceof UserPrincipal principal
                ? principal : null;
    }
}
