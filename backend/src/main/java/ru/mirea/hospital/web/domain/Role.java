package ru.mirea.hospital.web.domain;

/**
 * Роли прикладного доступа. Сервер проверяет их независимо от того, какие кнопки
 * скрыл конкретный клиент.
 */
public enum Role {
    PATIENT,
    VIEWER,
    EDITOR,
    ADMIN
}
