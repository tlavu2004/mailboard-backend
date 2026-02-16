-- Migration: V9__add_pg_trgm_fuzzy_search.sql
-- Description: Enable pg_trgm extension and create GIN trigram indexes for fuzzy search
-- This enables typo-tolerant search using PostgreSQL's trigram similarity

-- 1. Enable the pg_trgm extension for fuzzy string matching
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- 2. Create GIN trigram indexes on subject and sender for fast fuzzy search
CREATE INDEX IF NOT EXISTS idx_emails_subject_trgm
ON emails USING gin (subject gin_trgm_ops);

CREATE INDEX IF NOT EXISTS idx_emails_sender_trgm
ON emails USING gin (sender gin_trgm_ops);
