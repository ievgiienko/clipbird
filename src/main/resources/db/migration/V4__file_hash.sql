-- V4: Add file_hash column for transfer integrity verification (SHA-256 hex).

ALTER TABLE clipboard_files
    ADD COLUMN file_hash VARCHAR(64) DEFAULT NULL AFTER source_device_id;
