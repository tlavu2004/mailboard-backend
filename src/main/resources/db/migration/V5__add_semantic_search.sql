-- Migration: V5__add_semantic_search.sql
-- Description: Enable pgvector extension and add embedding column for Semantic Search
-- Note: Using 384 dimensions to support both local all-MiniLM-L6-v2 (384d) and Gemini (configured to 384d)

-- 1. Enable the vector extension (Supported by Neon DB)
CREATE EXTENSION IF NOT EXISTS vector;

-- 2. Add embedding column to emails table
-- We use 384 dimensions.
ALTER TABLE emails 
ADD COLUMN IF NOT EXISTS embedding vector(384);

-- 3. Create an HNSW index for faster similarity search
-- Using cosine distance (vector_cosine_ops)
CREATE INDEX IF NOT EXISTS idx_emails_embedding_hnsw 
ON emails USING hnsw (embedding vector_cosine_ops);
