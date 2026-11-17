package ru.mirea.hospital.web.domain;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Version;

import java.time.Instant;

/**
 * Общие технические поля изменяемых сущностей.
 *
 * <p>Поле {@link Version} используется для optimistic locking. При конфликте изменений
 * сервер возвращает 409 вместо перезаписи более новой версии данных.</p>
 */
@MappedSuperclass
public abstract class AuditedVersionedEntity {

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(nullable = false)
    private long version;

    /** Заполняет временные метки перед первой вставкой. */
    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    /** Обновляет техническую метку перед изменением. */
    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }

    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public long getVersion() { return version; }
    public void setVersion(long version) { this.version = version; }
}
