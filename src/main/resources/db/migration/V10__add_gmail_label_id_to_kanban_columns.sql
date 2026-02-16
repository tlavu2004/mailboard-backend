-- Migration V10: Add gmail_label_id to kanban_columns
ALTER TABLE kanban_columns ADD COLUMN gmail_label_id VARCHAR(255);

-- Optional: Comment to describe the column
COMMENT ON COLUMN kanban_columns.gmail_label_id IS 'Associated Gmail label ID (e.g., Label_1, INBOX, etc.) for syncing status';
