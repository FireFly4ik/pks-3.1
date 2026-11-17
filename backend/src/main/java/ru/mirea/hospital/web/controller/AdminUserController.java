package ru.mirea.hospital.web.controller;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.mirea.hospital.web.dto.PageResponse;
import ru.mirea.hospital.web.dto.UserDtos.CreateUserRequest;
import ru.mirea.hospital.web.dto.UserDtos.ResetPasswordRequest;
import ru.mirea.hospital.web.dto.UserDtos.UpdateUserRequest;
import ru.mirea.hospital.web.dto.UserDtos.UserResponse;
import ru.mirea.hospital.web.service.UserAdminService;
import ru.mirea.hospital.web.web.PageableFactory;

import java.util.UUID;

/** Административный REST-контур учётных записей. */
@RestController
@RequestMapping("/api/admin/users")
@SecurityRequirement(name = "bearerAuth")
@PreAuthorize("hasRole('ADMIN')")
public class AdminUserController {
    private final UserAdminService users;
    private final PageableFactory pageableFactory;

    public AdminUserController(UserAdminService users, PageableFactory pageableFactory) {
        this.users = users;
        this.pageableFactory = pageableFactory;
    }

    @GetMapping
    public PageResponse<UserResponse> list(@RequestParam(defaultValue = "0") int page,
                                           @RequestParam(defaultValue = "25") int size,
                                           @RequestParam(defaultValue = "email,asc") String sort) {
        return PageResponse.from(users.list(pageableFactory.users(page, size, sort)));
    }

    @PostMapping
    public ResponseEntity<UserResponse> create(@Valid @RequestBody CreateUserRequest request) {
        return ResponseEntity.status(201).body(users.create(request));
    }

    @PutMapping("/{id}")
    public UserResponse update(@PathVariable UUID id, @Valid @RequestBody UpdateUserRequest request) {
        return users.update(id, request);
    }

    @PostMapping("/{id}/reset-password")
    public ResponseEntity<Void> resetPassword(@PathVariable UUID id,
                                              @Valid @RequestBody ResetPasswordRequest request) {
        users.resetPassword(id, request);
        return ResponseEntity.noContent().build();
    }
}
