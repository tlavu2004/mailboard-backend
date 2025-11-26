-- Migration: V1__create_users_table.sql
-- Description: Add users table to store user information

CREATE TABLE IF NOT EXISTS users (
    id BIGSERIAL PRIMARY KEY,
    email VARCHAR(255) NOT NULL UNIQUE,
    password VARCHAR(255),
    google_id VARCHAR(255) UNIQUE,
    name VARCHAR(255) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    updated_at TIMESTAMP NOT NULL DEFAULT now()
);

-- Auto-update updated_at column on record update
CREATE OR REPLACE FUNCTION update_updated_at_column()
    RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = now();
    RETURN NEW;
END;
$$ language 'plpgsql';

CREATE TRIGGER trg_update_users_updated_at
    BEFORE UPDATE ON users
    FOR EACH ROW
EXECUTE FUNCTION update_updated_at_column();

-- Insert initial user data (Password: User@01!)
INSERT INTO users (email, password, google_id, name, created_at, updated_at)
VALUES (
        'user01@example.com',
        '$2a$12$FnBj1aHRSbYM1lF2P1REbeFg/Mx2Okqm.YPPf4nHIWstJWbkwZgkG',
        NULL,
        'User 01',
        now(),
        now()
)