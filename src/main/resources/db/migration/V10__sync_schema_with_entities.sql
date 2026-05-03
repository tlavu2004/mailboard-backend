-- V10: Sync schema with entity changes
-- Description: Add missing fields for Trash management, Gmail drafts, and Kanban UI

-- Add fields to emails table
ALTER TABLE emails 
ADD COLUMN IF NOT EXISTS gmail_draft_id VARCHAR(255),
ADD COLUMN IF NOT EXISTS previous_status VARCHAR(50),
ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMP;

-- Add fields to kanban_columns table
ALTER TABLE kanban_columns 
ADD COLUMN IF NOT EXISTS color VARCHAR(50) DEFAULT '#f1f5f9';

-- Create index for deleted_at to optimize trash cleanup
CREATE INDEX IF NOT EXISTS idx_emails_deleted_at ON emails(deleted_at);
