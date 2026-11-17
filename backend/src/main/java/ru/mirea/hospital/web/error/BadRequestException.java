package ru.mirea.hospital.web.error;

/** Сигнал некорректного прикладного запроса. */
public class BadRequestException extends RuntimeException {
    public BadRequestException(String message) { super(message); }
}
