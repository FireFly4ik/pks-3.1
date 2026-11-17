package ru.mirea.hospital.web.error;

/** Сигнал бизнес-конфликта или устаревшей версии объекта. */
public class ConflictException extends RuntimeException {
    public ConflictException(String message) { super(message); }
}
