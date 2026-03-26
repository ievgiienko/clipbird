package com.secureclipboard.repository;

import com.secureclipboard.domain.ChunkTransfer;
import com.secureclipboard.domain.TransferStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface ChunkTransferRepository extends JpaRepository<ChunkTransfer, Long> {

    Optional<ChunkTransfer> findByTransferCode(String transferCode);

    List<ChunkTransfer> findByClipboardId(Long clipboardId);

    List<ChunkTransfer> findByStatusInAndExpiresAtBefore(List<TransferStatus> statuses, Instant now);

    void deleteByClipboardId(Long clipboardId);

    List<ChunkTransfer> findBySourceDeviceIdAndStatusIn(String sourceDeviceId, List<TransferStatus> statuses);

    List<ChunkTransfer> findByFileFileCodeAndStatusIn(String fileCode, List<TransferStatus> statuses);
}
