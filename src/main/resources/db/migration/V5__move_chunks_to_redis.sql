-- V5: Move temporary chunk data from MySQL to Redis.
-- The temp_chunk_data and temp_chunk_iv columns are no longer used;
-- chunk binary data is now stored in Redis with TTL-based expiration.
-- temp_chunk_no is retained to track which chunk number is currently buffered.

ALTER TABLE chunk_transfers DROP COLUMN temp_chunk_data;
ALTER TABLE chunk_transfers DROP COLUMN temp_chunk_iv;
