-- Migration: V5_1__alter_embedding_to_768.sql
-- Description: Resize embedding column to 768 dimensions for embedding-001 compatibility

-- 1. Drop existing index (cannot alter column with index)
DROP INDEX IF EXISTS idx_emails_embedding_hnsw;

-- 2. Alter column type to vector(768)
-- Note: existing data (if any) might need to be truncated or re-calculated.
-- Since this is dev, we can clear the column or assume empty.
-- Using USING to cast if necessary, but pgvector might complain if dimensions mismatch on data.
-- Safest is to clear specific data or rebuild.
-- Here we just alter type. If fails due to existing data, user should clear data.
ALTER TABLE emails 
ALTER COLUMN embedding TYPE vector(768) USING NULL; -- Reset existing embeddings to NULL to avoid dimension error

-- 3. Re-create index for 768 dimensions
CREATE INDEX IF NOT EXISTS idx_emails_embedding_hnsw 
ON emails USING hnsw (embedding vector_cosine_ops);
