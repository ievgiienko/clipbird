CREATE TABLE clipboards (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    clipboard_code VARCHAR(12) NOT NULL UNIQUE,
    salt VARCHAR(64) NOT NULL,
    password_hash VARCHAR(128) NOT NULL,
    kdf_algorithm VARCHAR(20) NOT NULL DEFAULT 'PBKDF2',
    kdf_iterations INT NOT NULL DEFAULT 600000,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    INDEX idx_clipboard_code (clipboard_code),
    INDEX idx_status_expires (status, expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE clipboard_text_snapshots (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    clipboard_id BIGINT NOT NULL,
    ciphertext LONGBLOB,
    iv VARCHAR(64),
    version BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    updated_by_device VARCHAR(64),
    CONSTRAINT fk_text_clipboard FOREIGN KEY (clipboard_id) REFERENCES clipboards(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE clipboard_files (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    clipboard_id BIGINT NOT NULL,
    file_code VARCHAR(36) NOT NULL UNIQUE,
    encrypted_name TEXT NOT NULL,
    encrypted_name_iv VARCHAR(64) NOT NULL,
    encrypted_mime TEXT,
    encrypted_mime_iv VARCHAR(64),
    size_bytes BIGINT NOT NULL DEFAULT 0,
    chunk_size INT NOT NULL DEFAULT 5242880,
    total_chunks INT NOT NULL DEFAULT 0,
    source_device_id VARCHAR(64) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'AVAILABLE',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_file_clipboard FOREIGN KEY (clipboard_id) REFERENCES clipboards(id) ON DELETE CASCADE,
    INDEX idx_file_clipboard (clipboard_id),
    INDEX idx_file_code (file_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE chunk_transfers (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    transfer_code VARCHAR(36) NOT NULL UNIQUE,
    clipboard_id BIGINT NOT NULL,
    file_id BIGINT NOT NULL,
    source_device_id VARCHAR(64) NOT NULL,
    target_device_id VARCHAR(64) NOT NULL,
    current_chunk_no INT NOT NULL DEFAULT 0,
    total_chunks INT NOT NULL DEFAULT 0,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    temp_chunk_data LONGBLOB,
    temp_chunk_iv VARCHAR(64),
    expires_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_transfer_clipboard FOREIGN KEY (clipboard_id) REFERENCES clipboards(id) ON DELETE CASCADE,
    CONSTRAINT fk_transfer_file FOREIGN KEY (file_id) REFERENCES clipboard_files(id) ON DELETE CASCADE,
    INDEX idx_transfer_code (transfer_code),
    INDEX idx_transfer_status (status, expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
