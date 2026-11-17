package ru.mirea.hospital.web.service;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.mirea.hospital.web.domain.Role;
import ru.mirea.hospital.web.domain.Appointment;
import ru.mirea.hospital.web.domain.AppointmentNote;
import ru.mirea.hospital.web.domain.UserAccount;
import ru.mirea.hospital.web.dto.NoteDtos.NoteRequest;
import ru.mirea.hospital.web.dto.NoteDtos.NoteResponse;
import ru.mirea.hospital.web.error.ConflictException;
import ru.mirea.hospital.web.error.NotFoundException;
import ru.mirea.hospital.web.repository.AppointmentNoteRepository;
import ru.mirea.hospital.web.repository.AppointmentRepository;
import ru.mirea.hospital.web.security.CurrentUserService;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Управляет заметками и авторскими правами на их изменение. */
@Service
public class NoteService {
    private final AppointmentNoteRepository notes;
    private final AppointmentRepository appointments;
    private final CurrentUserService currentUser;
    private final AuditService audit;

    public NoteService(AppointmentNoteRepository notes, AppointmentRepository appointments,
                       CurrentUserService currentUser, AuditService audit) {
        this.notes = notes;
        this.appointments = appointments;
        this.currentUser = currentUser;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<NoteResponse> list(UUID appointmentId) {
        requireAppointment(appointmentId);
        return notes.findByAppointmentIdOrderByPinnedDescCreatedAtDesc(appointmentId).stream().map(NoteService::toResponse).toList();
    }

    @Transactional
    public NoteResponse create(UUID appointmentId, NoteRequest request) {
        Appointment appointment = requireAppointment(appointmentId);
        UserAccount actor = currentUser.requireEntity();
        AppointmentNote note = new AppointmentNote(UUID.randomUUID(), appointment, actor, request.body().trim(), request.pinned());
        note = notes.saveAndFlush(note);
        audit.record("NOTE_CREATED", "APPOINTMENT_NOTE", note.getId(), "Добавлена заметка к приёму", Map.of("appointmentId", appointmentId));
        return toResponse(note);
    }

    @Transactional
    public NoteResponse update(UUID appointmentId, UUID noteId, NoteRequest request) {
        AppointmentNote note = requireNote(appointmentId, noteId);
        UserAccount actor = currentUser.requireEntity();
        assertOwnerOrAdmin(note, actor);
        if (request.version() == null || request.version() != note.getVersion())
            throw new ConflictException("Заметка уже изменена. Обновите данные");
        note.setBody(request.body().trim());
        note.setPinned(request.pinned());
        notes.flush();
        audit.record("NOTE_UPDATED", "APPOINTMENT_NOTE", noteId, "Изменена заметка", Map.of("appointmentId", appointmentId));
        return toResponse(note);
    }

    @Transactional
    public void delete(UUID appointmentId, UUID noteId) {
        AppointmentNote note = requireNote(appointmentId, noteId);
        UserAccount actor = currentUser.requireEntity();
        assertOwnerOrAdmin(note, actor);
        notes.delete(note);
        audit.record("NOTE_DELETED", "APPOINTMENT_NOTE", noteId, "Удалена заметка", Map.of("appointmentId", appointmentId));
    }

    private Appointment requireAppointment(UUID id) {
        return appointments.findById(id).orElseThrow(() -> new NotFoundException("Приём не найден"));
    }

    private AppointmentNote requireNote(UUID appointmentId, UUID noteId) {
        AppointmentNote note = notes.findById(noteId).orElseThrow(() -> new NotFoundException("Заметка не найдена"));
        if (!note.getAppointment().getId().equals(appointmentId)) throw new NotFoundException("Заметка не найдена");
        return note;
    }

    private static void assertOwnerOrAdmin(AppointmentNote note, UserAccount actor) {
        if (!note.getAuthor().getId().equals(actor.getId()) && !actor.getRoles().contains(Role.ADMIN)) {
            throw new AccessDeniedException("Only note author or administrator can modify it");
        }
    }

    private static NoteResponse toResponse(AppointmentNote note) {
        return new NoteResponse(note.getId(), note.getAppointment().getId(), note.getAuthor().getId(),
                note.getAuthor().getDisplayName(), note.getBody(), note.isPinned(),
                note.getCreatedAt(), note.getUpdatedAt(), note.getVersion());
    }
}
