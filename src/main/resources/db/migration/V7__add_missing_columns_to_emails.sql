-- Migration: V7__add_missing_columns_to_emails
-- Description: Add missing columns to emails table for feature compatibility

ALTER TABLE emails 
ADD COLUMN IF NOT EXISTS is_starred BOOLEAN NOT NULL DEFAULT FALSE,
ADD COLUMN IF NOT EXISTS gmail_message_id VARCHAR(255),
ADD COLUMN IF NOT EXISTS thread_id VARCHAR(255),
ADD COLUMN IF NOT EXISTS summary_source VARCHAR(50);

-- Add index for starred filtering
CREATE INDEX IF NOT EXISTS idx_emails_is_starred ON emails(is_starred);
