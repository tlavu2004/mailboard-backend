-- Add Gmail Watch metadata columns to email_accounts table
ALTER TABLE email_accounts ADD COLUMN IF NOT EXISTS watch_expiration TIMESTAMP;
ALTER TABLE email_accounts ADD COLUMN IF NOT EXISTS watch_history_id BIGINT;
