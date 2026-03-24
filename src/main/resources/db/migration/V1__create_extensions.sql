-- Migration: V1__create_extensions
-- Description: Enable necessary PostgreSQL extensions

CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS pg_trgm;
