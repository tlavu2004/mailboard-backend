-- Migration: V4__create_emails_table.sql
-- Description: Create 'emails' table for storing synchronized email data
-- Replaces previous auto-generated schema (from Hibernate ddl-auto)

CREATE TABLE IF NOT EXISTS emails (
    id BIGSERIAL PRIMARY KEY,
    
    -- Relationships
    account_id BIGINT NOT NULL,
    
    -- Core identification
    message_id VARCHAR(255) NOT NULL UNIQUE,
    uid BIGINT,
    
    -- Check constraint for message_id uniqueness is handled by UNIQUE above
    
    -- Content
    subject VARCHAR(255),
    sender VARCHAR(255),
    snippet VARCHAR(500),
    body TEXT,
    
    -- Metadata & Status
    status VARCHAR(50) DEFAULT 'INBOX',
    received_date TIMESTAMP,
    snoozed_until TIMESTAMP,
    summary TEXT,
    
    -- Filtering flags (New columns)
    is_read BOOLEAN NOT NULL DEFAULT FALSE,
    has_attachments BOOLEAN NOT NULL DEFAULT FALSE,
    
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    
    -- Foreign Key Constraint
    CONSTRAINT fk_emails_account FOREIGN KEY (account_id) 
        REFERENCES email_accounts(id) ON DELETE CASCADE
);

-- Indexes for performance
CREATE INDEX IF NOT EXISTS idx_emails_account_id ON emails(account_id);
CREATE INDEX IF NOT EXISTS idx_emails_message_id ON emails(message_id);
CREATE INDEX IF NOT EXISTS idx_emails_status ON emails(status);
CREATE INDEX IF NOT EXISTS idx_emails_snoozed ON emails(snoozed_until);
