package ru.mirea.hospital.exception;

public class EntityNotFoundException extends BusinessException {
    public EntityNotFoundException(String entity) { super(entity + " не найден(а)."); }
}
