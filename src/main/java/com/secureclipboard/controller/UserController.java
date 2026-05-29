package com.secureclipboard.controller;

import com.secureclipboard.domain.User;
import com.secureclipboard.dto.ClipboardPageResponse;
import com.secureclipboard.dto.UserClipboardSummary;
import com.secureclipboard.service.AuthService;
import com.secureclipboard.service.ClipboardService;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final AuthService authService;
    private final ClipboardService clipboardService;

    public UserController(AuthService authService, ClipboardService clipboardService) {
        this.authService = authService;
        this.clipboardService = clipboardService;
    }

    @GetMapping("/me/clipboards")
    public ResponseEntity<ClipboardPageResponse> getMyClipboards(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            Authentication authentication) {

        User user = authService.findByEmail(authentication.getName());

        Pageable pageable = PageRequest.of(
            Math.max(0, page),
            Math.min(Math.max(1, size), 100),
            Sort.by("createdAt").descending()
        );

        var clipboardPage = clipboardService.getClipboardsForUser(user, pageable);

        List<UserClipboardSummary> items = clipboardPage.getContent().stream()
            .map(c -> new UserClipboardSummary(
                c.getClipboardCode(),
                c.getCreatedAt(),
                c.getExpiresAt(),
                c.getStatus().name()
            ))
            .toList();

        return ResponseEntity.ok(new ClipboardPageResponse(
            items,
            clipboardPage.getTotalPages(),
            clipboardPage.getTotalElements()
        ));
    }
}
