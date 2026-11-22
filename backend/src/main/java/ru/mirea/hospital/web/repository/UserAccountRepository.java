package ru.mirea.hospital.web.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.mirea.hospital.web.domain.UserAccount;

import java.util.Optional;
import java.util.UUID;

/** Persistence gateway for user accounts. */
public interface UserAccountRepository extends JpaRepository<UserAccount, UUID> {
    @org.springframework.data.jpa.repository.Query(value = "select a.display_name from app_user a join users u on u.web_account_id = a.id where u.login = :login", nativeQuery = true)
    Optional<String> findEmailByPatientLogin(String login);

    Optional<UserAccount> findByEmailIgnoreCase(String email);
    boolean existsByEmailIgnoreCase(String email);
}
