package com.secureclipboard.controller;

import com.secureclipboard.domain.User;
import com.secureclipboard.dto.*;
import com.secureclipboard.service.AuthService;
import com.secureclipboard.service.ClipboardService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/clipboards")
public class ClipboardController {

    private final ClipboardService clipboardService;
    private final AuthService authService;

    public ClipboardController(ClipboardService clipboardService, AuthService authService) {
        this.clipboardService = clipboardService;
        this.authService = authService;
    }

    @PostMapping
    public ResponseEntity<ClipboardCreatedResponse> createClipboard(
            @RequestBody(required = false) CreateClipboardRequest request,
            Authentication authentication) {
        User owner = resolveUser(authentication);
        ClipboardCreatedResponse response = clipboardService.createClipboard(request, owner);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/{clipboardCode}/password")
    public ResponseEntity<Void> setPassword(@PathVariable String clipboardCode,
                                             @Valid @RequestBody OpenClipboardRequest request) {
        clipboardService.setPasswordHash(clipboardCode, request.passwordHash());
        return ResponseEntity.ok().build();
    }

    @GetMapping("/{clipboardCode}/handshake")
    public ResponseEntity<HandshakeResponse> getHandshake(@PathVariable String clipboardCode) {
        HandshakeResponse response = clipboardService.getHandshake(clipboardCode);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{clipboardCode}/verify")
    public ResponseEntity<Void> verifyPassword(@PathVariable String clipboardCode,
                                                @Valid @RequestBody OpenClipboardRequest request) {
        boolean valid = clipboardService.verifyPassword(clipboardCode, request.passwordHash());
        if (!valid) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        return ResponseEntity.ok().build();
    }

    @GetMapping("/{clipboardCode}/state")
    public ResponseEntity<ClipboardStateResponse> getState(@PathVariable String clipboardCode) {
        ClipboardStateResponse response = clipboardService.getState(clipboardCode);
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/{clipboardCode}")
    public ResponseEntity<Void> deleteClipboard(@PathVariable String clipboardCode,
                                                 Authentication authentication) {
        User requestingUser = resolveUser(authentication);
        clipboardService.deleteClipboard(clipboardCode, requestingUser);
        return ResponseEntity.noContent().build();
    }

    private User resolveUser(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || "anonymousUser".equals(authentication.getPrincipal())) {
            return null;
        }
        return authService.findByEmail(authentication.getName());
    }

    @PostMapping("/{clipboardCode}/files")
    public ResponseEntity<FileManifestDto> addFile(@PathVariable String clipboardCode,
                                                    @Valid @RequestBody AddFileRequest request) {
        FileManifestDto response = clipboardService.addFile(clipboardCode, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @DeleteMapping("/{clipboardCode}/files/{fileCode}")
    public ResponseEntity<Void> removeFile(@PathVariable String clipboardCode,
                                           @PathVariable String fileCode) {
        clipboardService.removeFile(clipboardCode, fileCode);
        return ResponseEntity.noContent().build();
    }
}
