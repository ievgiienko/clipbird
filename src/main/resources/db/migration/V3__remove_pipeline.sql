-- V3: Remove double-buffer and pipeline columns (revert to simple sequential transfer).
-- Only slot A (temp_chunk_data, temp_chunk_iv, temp_chunk_no) is retained.

ALTER TABLE chunk_transfers
    DROP COLUMN temp_chunk_data_b,
    DROP COLUMN temp_chunk_iv_b,
    DROP COLUMN temp_chunk_no_b,
    DROP COLUMN pipeline_depth;
