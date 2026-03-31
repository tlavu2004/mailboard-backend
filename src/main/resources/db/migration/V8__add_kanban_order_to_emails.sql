-- Migration: V8__add_kanban_order_to_emails
-- Description: Add kanban_order column for manual reordering of cards

ALTER TABLE emails 
ADD COLUMN IF NOT EXISTS kanban_order DOUBLE PRECISION;

-- Initialize kanban_order based on received_date to maintain current order
UPDATE emails 
SET kanban_order = EXTRACT(EPOCH FROM received_date);

-- Add index for sorting performance
CREATE INDEX IF NOT EXISTS idx_emails_kanban_order ON emails(kanban_order DESC);
