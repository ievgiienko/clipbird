package com.secureclipboard.repository;

import com.secureclipboard.domain.ClipboardFile;
import com.secureclipboard.domain.FileStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ClipboardFileRepository extends JpaRepository<ClipboardFile, Long> {

    List<ClipboardFile> findByClipboardIdAndStatus(Long clipboardId, FileStatus status);

    Optional<ClipboardFile> findByFileCode(String fileCode);

    void deleteByClipboardId(Long clipboardId);
}
