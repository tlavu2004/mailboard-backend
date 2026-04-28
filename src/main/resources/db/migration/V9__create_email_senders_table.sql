CREATE TABLE email_senders (
    id BIGSERIAL PRIMARY KEY,
    email VARCHAR(255) NOT NULL UNIQUE,
    best_known_name VARCHAR(255)
);

CREATE INDEX idx_email_senders_email ON email_senders(email);
