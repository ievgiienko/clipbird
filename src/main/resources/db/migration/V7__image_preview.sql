ALTER TABLE clipboard_files
    ADD COLUMN encrypted_preview TEXT NULL AFTER file_hash,
    ADD COLUMN encrypted_preview_iv VARCHAR(64) NULL AFTER encrypted_preview;
