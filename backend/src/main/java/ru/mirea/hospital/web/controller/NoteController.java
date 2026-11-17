package ru.mirea.hospital.web.controller;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.mirea.hospital.web.dto.NoteDtos.NoteRequest;
import ru.mirea.hospital.web.dto.NoteDtos.NoteResponse;
import ru.mirea.hospital.web.service.NoteService;

import java.util.List;
import java.util.UUID;

/** REST-контур преподавательских заметок. */
@RestController
@RequestMapping("/api/appointments/{appointmentId}/notes")
@SecurityRequirement(name = "bearerAuth")
@PreAuthorize("hasAnyRole('EDITOR','ADMIN')")
public class NoteController {
    private final NoteService notes;

    public NoteController(NoteService notes) { this.notes = notes; }

    @GetMapping
    public List<NoteResponse> list(@PathVariable UUID appointmentId) { return notes.list(appointmentId); }

    @PostMapping
    public ResponseEntity<NoteResponse> create(@PathVariable UUID appointmentId,
                                               @Valid @RequestBody NoteRequest request) {
        return ResponseEntity.status(201).body(notes.create(appointmentId, request));
    }

    @PutMapping("/{noteId}")
    public NoteResponse update(@PathVariable UUID appointmentId, @PathVariable UUID noteId,
                               @Valid @RequestBody NoteRequest request) {
        return notes.update(appointmentId, noteId, request);
    }

    @DeleteMapping("/{noteId}")
    public ResponseEntity<Void> delete(@PathVariable UUID appointmentId, @PathVariable UUID noteId) {
        notes.delete(appointmentId, noteId);
        return ResponseEntity.noContent().build();
    }
}
