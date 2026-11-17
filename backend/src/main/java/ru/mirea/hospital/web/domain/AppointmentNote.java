package ru.mirea.hospital.web.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.util.UUID;

/** Регистраторская заметка, привязанная к карточке приёма. */
@Entity
@Table(name = "appointment_note")
public class AppointmentNote extends AuditedVersionedEntity {
    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "appointment_id", nullable = false)
    private Appointment appointment;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "author_id", nullable = false)
    private UserAccount author;

    @Column(nullable = false, length = 4000)
    private String body;

    @Column(nullable = false)
    private boolean pinned;

    protected AppointmentNote() {
    }

    public AppointmentNote(UUID id, Appointment appointment, UserAccount author, String body, boolean pinned) {
        this.id = id;
        this.appointment = appointment;
        this.author = author;
        this.body = body;
        this.pinned = pinned;
    }

    public UUID getId() { return id; }
    public Appointment getAppointment() { return appointment; }
    public UserAccount getAuthor() { return author; }
    public String getBody() { return body; }
    public void setBody(String body) { this.body = body; }
    public boolean isPinned() { return pinned; }
    public void setPinned(boolean pinned) { this.pinned = pinned; }
}
