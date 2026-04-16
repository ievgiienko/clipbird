package com.secureclipboard.repository;

import com.secureclipboard.domain.ClipboardFile;
import com.secureclipboard.domain.FileStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface ClipboardFileRepository extends JpaRepository<ClipboardFile, Long> {

    List<ClipboardFile> findByClipboardIdAndStatus(Long clipboardId, FileStatus status);

    Optional<ClipboardFile> findByFileCode(String fileCode);

    void deleteByClipboardId(Long clipboardId);

    // --- Stats ---

    @Query("SELECT COUNT(f) FROM ClipboardFile f WHERE f.createdAt >= :from AND f.createdAt < :to")
    long countUploadedBetween(Instant from, Instant to);

    @Query("SELECT COALESCE(SUM(f.sizeBytes), 0) FROM ClipboardFile f WHERE f.createdAt >= :from AND f.createdAt < :to")
    long sumBytesBetween(Instant from, Instant to);
}
