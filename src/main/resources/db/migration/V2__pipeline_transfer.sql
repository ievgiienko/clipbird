-- V2: Add second temp chunk slot for pipelined (double-buffered) transfers.
-- Slot 0 = existing columns (temp_chunk_data, temp_chunk_iv).
-- Slot 1 = new columns (temp_chunk_data_b, temp_chunk_iv_b, temp_chunk_no_b).
-- Also add explicit chunk-number tracking for slot 0.

ALTER TABLE chunk_transfers
    ADD COLUMN temp_chunk_no INT DEFAULT NULL AFTER temp_chunk_iv,
    ADD COLUMN temp_chunk_data_b LONGBLOB DEFAULT NULL AFTER temp_chunk_no,
    ADD COLUMN temp_chunk_iv_b VARCHAR(64) DEFAULT NULL AFTER temp_chunk_data_b,
    ADD COLUMN temp_chunk_no_b INT DEFAULT NULL AFTER temp_chunk_iv_b,
    ADD COLUMN pipeline_depth INT NOT NULL DEFAULT 1 AFTER total_chunks;
