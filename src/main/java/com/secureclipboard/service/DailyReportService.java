package com.secureclipboard.service;

import com.secureclipboard.repository.ClipboardFileRepository;
import com.secureclipboard.repository.ClipboardRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

@Service
public class DailyReportService {

    private final ClipboardRepository clipboardRepo;
    private final ClipboardFileRepository fileRepo;

    public DailyReportService(ClipboardRepository clipboardRepo,
                               ClipboardFileRepository fileRepo) {
        this.clipboardRepo = clipboardRepo;
        this.fileRepo = fileRepo;
    }

    @Transactional(readOnly = true)
    public String buildReport() {
        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
        Instant from = LocalDate.now(ZoneOffset.UTC).atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant to = now.toInstant();

        long clipboardsCreated  = clipboardRepo.countCreatedBetween(from, to);
        long clipboardsExpired  = clipboardRepo.countExpiredBetween(from, to);
        long activeClipboards   = clipboardRepo.countActive();
        long filesUploaded      = fileRepo.countUploadedBetween(from, to);
        long bytesTransferred   = fileRepo.sumBytesBetween(from, to);

        String date = LocalDate.now(ZoneOffset.UTC).toString();

        return """
                <b>Daily Report — %s</b>

                Clipboards created:  <b>%d</b>
                Clipboards expired:  <b>%d</b>
                Active clipboards:   <b>%d</b>

                Files uploaded:      <b>%d</b>
                Data transferred:    <b>%s</b>
                """.formatted(date, clipboardsCreated, clipboardsExpired, activeClipboards,
                filesUploaded, formatBytes(bytesTransferred));
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1_024L)
            return bytes + " B";
        double kb = bytes / 1_024.0;
        if (kb < 1_024)
            return "%.1f KB".formatted(kb);
        double mb = kb / 1_024.0;
        if (mb < 1_024)
            return "%.2f MB".formatted(mb);
        return "%.2f GB".formatted(mb / 1_024.0);
    }
}
