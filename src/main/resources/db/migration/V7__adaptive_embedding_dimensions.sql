-- Migration: V7__adaptive_embedding_dimensions.sql
-- Description: Support both 384-dim (ONNX local) and 768-dim (Gemini) embeddings

-- 1. Drop existing index
DROP INDEX IF EXISTS idx_emails_embedding_hnsw;

-- 2. Rename existing embedding column to embedding_768 (Gemini)
ALTER TABLE emails RENAME COLUMN embedding TO embedding_768;

-- 3. Add new embedding_384 column (ONNX local model)
ALTER TABLE emails ADD COLUMN IF NOT EXISTS embedding_384 vector(384);

-- 4. Add embedding_model column to track which model generated the embedding
ALTER TABLE emails ADD COLUMN IF NOT EXISTS embedding_model VARCHAR(50);

-- 5. Create HNSW indexes for both dimensions
CREATE INDEX IF NOT EXISTS idx_emails_embedding_768_hnsw 
ON emails USING hnsw (embedding_768 vector_cosine_ops);

CREATE INDEX IF NOT EXISTS idx_emails_embedding_384_hnsw 
ON emails USING hnsw (embedding_384 vector_cosine_ops);
