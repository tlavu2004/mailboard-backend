-- Migration: V7__create_email_attachments_table
-- Description: Create email_attachments table with external_url for cloud links

CREATE TABLE IF NOT EXISTS email_attachments (
    id BIGSERIAL PRIMARY KEY,
    email_id BIGINT NOT NULL,
    filename VARCHAR(255) NOT NULL,
    content_type VARCHAR(255),
    size BIGINT NOT NULL,
    server_attachment_id VARCHAR(255),
    content_id VARCHAR(255),
    external_url VARCHAR(1024),
    inline BOOLEAN NOT NULL DEFAULT FALSE,
    CONSTRAINT fk_email_attachments_email FOREIGN KEY (email_id) 
        REFERENCES emails(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_email_attachments_email_id ON email_attachments(email_id);
