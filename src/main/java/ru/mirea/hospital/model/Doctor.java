package ru.mirea.hospital.model;

public record Doctor(long id, String fullName, String specialty) {
    @Override public String toString() { return fullName + " — " + specialty; }
}
