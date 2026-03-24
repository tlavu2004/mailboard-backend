-- Migration: V5__create_emails_table
-- Description: Create emails table with semantic search and fuzzy search support

CREATE TABLE IF NOT EXISTS emails (
    id BIGSERIAL PRIMARY KEY,
    account_id BIGINT NOT NULL,
    message_id VARCHAR(255) NOT NULL UNIQUE,
    uid BIGINT,
    subject VARCHAR(255),
    sender VARCHAR(255),
    snippet VARCHAR(500),
    body TEXT,
    status VARCHAR(50) DEFAULT 'INBOX',
    received_date TIMESTAMP,
    snoozed_until TIMESTAMP,
    summary TEXT,
    is_read BOOLEAN NOT NULL DEFAULT FALSE,
    has_attachments BOOLEAN NOT NULL DEFAULT FALSE,
    
    -- Semantic Search Embeddings
    embedding_768 vector(768),
    embedding_384 vector(384),
    
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    
    CONSTRAINT fk_emails_account FOREIGN KEY (account_id) 
        REFERENCES email_accounts(id) ON DELETE CASCADE
);

-- Basic Indexes
CREATE INDEX idx_emails_account_id ON emails(account_id);
CREATE INDEX idx_emails_status ON emails(status);
CREATE INDEX idx_emails_snoozed ON emails(snoozed_until);

-- Semantic Search Indexes (HNSW)
CREATE INDEX idx_emails_embedding_768_hnsw ON emails USING hnsw (embedding_768 vector_cosine_ops);
CREATE INDEX idx_emails_embedding_384_hnsw ON emails USING hnsw (embedding_384 vector_cosine_ops);

-- Fuzzy Search Indexes (GIN Trigram)
CREATE INDEX idx_emails_subject_trgm ON emails USING gin (subject gin_trgm_ops);
CREATE INDEX idx_emails_sender_trgm ON emails USING gin (sender gin_trgm_ops);
