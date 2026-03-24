-- Migration: V6__create_kanban_columns_table
-- Description: Create kanban_columns table with Gmail label mapping

CREATE TABLE IF NOT EXISTS kanban_columns (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    position INTEGER NOT NULL,
    linked_status VARCHAR(50),
    account_id BIGINT NOT NULL,
    gmail_label_id VARCHAR(255),
    created_at TIMESTAMP WITHOUT TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITHOUT TIME ZONE DEFAULT NOW(),
    
    CONSTRAINT fk_kanban_columns_account 
        FOREIGN KEY (account_id) 
        REFERENCES email_accounts(id) 
        ON DELETE CASCADE
);

CREATE INDEX idx_kanban_columns_account_id ON kanban_columns(account_id);
COMMENT ON COLUMN kanban_columns.gmail_label_id IS 'Associated Gmail label ID for syncing status';
