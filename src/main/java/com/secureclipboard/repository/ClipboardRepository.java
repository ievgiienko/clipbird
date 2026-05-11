package com.secureclipboard.repository;

import com.secureclipboard.domain.Clipboard;
import com.secureclipboard.domain.ClipboardStatus;
import com.secureclipboard.domain.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface ClipboardRepository extends JpaRepository<Clipboard, Long> {

    Optional<Clipboard> findByClipboardCodeAndStatus(String clipboardCode, ClipboardStatus status);

    Optional<Clipboard> findByClipboardCode(String clipboardCode);

    boolean existsByClipboardCode(String clipboardCode);

    List<Clipboard> findByStatusAndExpiresAtBefore(ClipboardStatus status, Instant now);

    @Modifying
    @Query("UPDATE Clipboard c SET c.status = :status WHERE c.id = :id")
    void updateStatus(Long id, ClipboardStatus status);

    List<Clipboard> findByStatusInAndExpiresAtBefore(List<ClipboardStatus> statuses, Instant cutoff);

    // --- User dashboard ---

    Page<Clipboard> findByOwnerAndStatusNot(User owner, ClipboardStatus excludedStatus, Pageable pageable);

    // --- Stats ---

    @Query("SELECT COUNT(c) FROM Clipboard c WHERE c.createdAt >= :from AND c.createdAt < :to")
    long countCreatedBetween(Instant from, Instant to);

    @Query("SELECT COUNT(c) FROM Clipboard c WHERE c.expiresAt >= :from AND c.expiresAt < :to")
    long countExpiredBetween(Instant from, Instant to);

    @Query("SELECT COUNT(c) FROM Clipboard c WHERE c.status = com.secureclipboard.domain.ClipboardStatus.ACTIVE")
    long countActive();
}
