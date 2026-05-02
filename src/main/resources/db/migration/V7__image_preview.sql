ALTER TABLE clipboard_files
    ADD COLUMN encrypted_preview LONGTEXT,
ADD COLUMN encrypted_preview_iv VARCHAR(255);