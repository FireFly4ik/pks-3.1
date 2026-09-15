package ru.mirea.hospital.model;

public enum AppointmentStatus {
    IN_PROGRESS("В процессе"), UPCOMING("Предстоит"), COMPLETED("Завершена"), CANCELLED("Отменена");
    private final String title;
    AppointmentStatus(String title) { this.title = title; }
    @Override public String toString() { return title; }
}
