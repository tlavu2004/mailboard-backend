-- Create email_accounts table for storing linked email accounts (IMAP/SMTP)
-- Part of GA04 Track B implementation

CREATE TABLE IF NOT EXISTS email_accounts (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    email_address VARCHAR(255) NOT NULL,
    display_name VARCHAR(255),
    provider VARCHAR(50) NOT NULL,
    auth_type VARCHAR(50) NOT NULL,
    
    -- IMAP Configuration
    imap_host VARCHAR(255) NOT NULL,
    imap_port INTEGER NOT NULL DEFAULT 993,
    imap_ssl BOOLEAN NOT NULL DEFAULT TRUE,
    
    -- SMTP Configuration
    smtp_host VARCHAR(255) NOT NULL,
    smtp_port INTEGER NOT NULL DEFAULT 587,
    smtp_starttls BOOLEAN NOT NULL DEFAULT TRUE,
    
    -- Credentials (encrypted)
    username VARCHAR(255) NOT NULL,
    encrypted_password TEXT,
    encrypted_refresh_token TEXT,
    
    -- Status & Metadata
    active BOOLEAN NOT NULL DEFAULT TRUE,
    last_sync_at TIMESTAMP,
    last_error TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    
    -- Constraints
    CONSTRAINT uk_email_accounts_user_email UNIQUE (user_id, email_address)
);

-- Index for faster lookups
CREATE INDEX IF NOT EXISTS idx_email_accounts_user_id ON email_accounts(user_id);
CREATE INDEX IF NOT EXISTS idx_email_accounts_active ON email_accounts(user_id, active);

-- Add comment for documentation
COMMENT ON TABLE email_accounts IS 'Stores linked external email accounts for IMAP/SMTP access';
COMMENT ON COLUMN email_accounts.encrypted_password IS 'AES-256-GCM encrypted password or OAuth access token';
COMMENT ON COLUMN email_accounts.encrypted_refresh_token IS 'AES-256-GCM encrypted OAuth refresh token';
