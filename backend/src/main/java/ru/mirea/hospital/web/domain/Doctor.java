package ru.mirea.hospital.web.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

/** Врач, на которого ссылаются карточки приёмов. */
@Entity
@Table(name = "doctors")
public class Doctor extends AuditedVersionedEntity {
    @Id
    @Column(name="public_id")
    private UUID id;

    @Column(nullable = false, unique = true, length = 50)
    private String code;

    @Column(name="full_name",length=120,nullable=false)
    private String name;

    @Column(name = "specialty")
    private String specialty;

    @Column(nullable = false)
    private boolean active = true;

    protected Doctor() {
    }

    public Doctor(UUID id, String code, String name, String specialty) {
        this.id = id;
        this.code = code;
        this.name = name;
        this.specialty = specialty;
    }

    public UUID getId() { return id; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getSpecialty() { return specialty; }
    public void setSpecialty(String specialty) { this.specialty = specialty; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
}
