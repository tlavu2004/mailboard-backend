-- Migration: V8__remove_redundant_embedding_model.sql
-- Description: Remove redundant embedding_model column

ALTER TABLE emails DROP COLUMN IF EXISTS embedding_model;
