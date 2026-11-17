package ru.mirea.hospital.web.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import ru.mirea.hospital.web.domain.RefreshSession;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Persistence gateway for rotating refresh sessions. */
public interface RefreshSessionRepository extends JpaRepository<RefreshSession, UUID> {
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    Optional<RefreshSession> findByTokenHash(String tokenHash);

    @Modifying
    @Query("update RefreshSession s set s.revokedAt = :now where s.user.id = :userId and s.revokedAt is null")
    int revokeAllForUser(UUID userId, Instant now);

    long deleteByExpiresAtBefore(Instant threshold);
}
