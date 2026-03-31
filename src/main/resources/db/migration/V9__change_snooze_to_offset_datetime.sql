-- Migration: V9__change_snooze_to_offset_datetime
-- Description: Convert snoozed_until to TIMESTAMP WITH TIME ZONE for absolute time tracking

ALTER TABLE emails 
ALTER COLUMN snoozed_until TYPE TIMESTAMP WITH TIME ZONE;
