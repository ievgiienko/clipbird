package com.secureclipboard.repository;

import com.secureclipboard.domain.ClipboardTextSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ClipboardTextSnapshotRepository extends JpaRepository<ClipboardTextSnapshot, Long> {

    Optional<ClipboardTextSnapshot> findByClipboardId(Long clipboardId);

    void deleteByClipboardId(Long clipboardId);
}
