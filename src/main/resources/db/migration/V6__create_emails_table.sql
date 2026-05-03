-- Migration: V6__create_emails_table
-- Description: Create consolidated emails table with semantic search and metadata

CREATE TABLE IF NOT EXISTS emails (
    id BIGSERIAL PRIMARY KEY,
    account_id BIGINT NOT NULL,
    message_id VARCHAR(255) NOT NULL UNIQUE,
    gmail_message_id VARCHAR(255),
    gmail_draft_id VARCHAR(255),
    thread_id VARCHAR(255),
    uid BIGINT,
    subject VARCHAR(255),
    sender VARCHAR(255),
    from_name VARCHAR(255),
    recipient_to TEXT,
    recipient_cc TEXT,
    snippet VARCHAR(500),
    body TEXT,
    status VARCHAR(50) DEFAULT 'INBOX',
    previous_status VARCHAR(50),
    kanban_order DOUBLE PRECISION,
    received_date TIMESTAMP,
    snoozed_until TIMESTAMP WITH TIME ZONE,
    deleted_at TIMESTAMP,
    summary TEXT,
    summary_source VARCHAR(50),
    is_read BOOLEAN NOT NULL DEFAULT FALSE,
    is_starred BOOLEAN NOT NULL DEFAULT FALSE,
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
CREATE INDEX idx_emails_is_starred ON emails(is_starred);
CREATE INDEX idx_emails_kanban_order ON emails(kanban_order DESC);
CREATE INDEX idx_emails_deleted_at ON emails(deleted_at);

-- Semantic Search Indexes (HNSW)
CREATE INDEX idx_emails_embedding_768_hnsw ON emails USING hnsw (embedding_768 vector_cosine_ops);
CREATE INDEX idx_emails_embedding_384_hnsw ON emails USING hnsw (embedding_384 vector_cosine_ops);

-- Fuzzy Search Indexes (GIN Trigram)
CREATE INDEX idx_emails_subject_trgm ON emails USING gin (subject gin_trgm_ops);
CREATE INDEX idx_emails_sender_trgm ON emails USING gin (sender gin_trgm_ops);
