package ru.mirea.hospital.web.controller;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.mirea.hospital.web.dto.DoctorDtos.DoctorRequest;
import ru.mirea.hospital.web.dto.DoctorDtos.DoctorResponse;
import ru.mirea.hospital.web.service.DoctorService;

import java.util.List;
import java.util.UUID;

/** REST-контур врачей. */
@RestController
@RequestMapping("/api/doctors")
@SecurityRequirement(name = "bearerAuth")
public class DoctorController {
    private final DoctorService doctors;

    public DoctorController(DoctorService doctors) { this.doctors = doctors; }

    @GetMapping
    @PreAuthorize("hasAnyRole('VIEWER','EDITOR','ADMIN')")
    public List<DoctorResponse> list(@RequestParam(defaultValue = "false") boolean activeOnly) {
        return doctors.list(activeOnly);
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<DoctorResponse> create(@Valid @RequestBody DoctorRequest request) {
        return ResponseEntity.status(201).body(doctors.create(request));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public DoctorResponse update(@PathVariable UUID id, @Valid @RequestBody DoctorRequest request) {
        return doctors.update(id, request);
    }

    @PatchMapping("/{id}/active")
    @PreAuthorize("hasRole('ADMIN')")
    public DoctorResponse setActive(@PathVariable UUID id, @RequestParam boolean active, @RequestParam long version) {
        return doctors.setActive(id, active, version);
    }
}
