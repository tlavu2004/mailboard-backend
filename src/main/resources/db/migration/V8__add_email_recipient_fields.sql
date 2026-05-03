-- V8: Add support for separate sender name and recipient lists
ALTER TABLE emails 
ADD COLUMN from_name VARCHAR(255),
ADD COLUMN recipient_to TEXT,
ADD COLUMN recipient_cc TEXT;
