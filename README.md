# MailBoard - AI-Powered Email Management Platform

MailBoard is a full-featured, AI-powered email management platform built with **Java 21** and **Spring Boot 3**. It integrates directly with **Gmail** via OAuth 2.0, IMAP, and the Gmail API, providing intelligent email triage through a dynamic **Kanban Board**, **AI Summarization** (Gemini), and **Semantic Search** (pgvector).

> [!NOTE]
> This is the **Backend** repository (REST API + PostgreSQL + pgvector).
> The **Frontend** (Next.js + TypeScript) is managed in a separate repository: [mailboard-frontend](https://github.com/tlavu2004/mailboard-frontend)

---

## Table of Contents

- [Key Features](#key-features)
- [Architecture](#architecture)
- [Database Schema](#database-schema)
- [Tech Stack](#tech-stack)
- [API Overview](#api-overview)
- [Getting Started](#getting-started)
- [Environment Variables](#environment-variables)
- [Deployment](#deployment)
- [Authors](#authors)
- [License](#license)

---

## Key Features

### Gmail Integration
Full bidirectional integration with Gmail via OAuth 2.0:
- **IMAP Sync**: Fetch and sync emails from Gmail with incremental updates via UID tracking.
- **Gmail Watch API**: Real-time push notifications via Google Cloud Pub/Sub — new emails appear instantly.
- **Send via SMTP**: Compose, reply, and forward emails using XOAUTH2-authenticated SMTP.
- **Label Management**: Read and modify Gmail labels directly from the Kanban board.

### Kanban Board
A fully customizable Kanban interface for email triage:
- **Dynamic Columns**: Create, rename, reorder, and color-code columns via a Settings modal.
- **Drag-and-Drop**: Move emails between columns with status persistence and Gmail label sync.
- **Gmail Label Mapping**: Each Kanban column can be linked to a Gmail label for bidirectional sync.
- **Snooze Mechanism**: Snooze emails to a specific datetime; a `@Scheduled` task auto-returns them to Inbox.

### AI Features (Gemini)
Intelligent email processing powered by Google Gemini:
- **Email Summarization**: On-demand AI summaries with an extractive fallback when the API is unavailable.
- **Embedding Generation**: Automatic batch embedding generation using Gemini (`gemini-embedding-001`) or a local ONNX model as fallback.
- **Composite Service Pattern**: `CompositeEmbeddingService` tries Gemini first, then falls back to local ONNX — ensuring zero-downtime embedding generation.

### Search
Dual-mode search combining traditional text matching with semantic understanding:
- **Fuzzy Search**: Typo-tolerant search powered by PostgreSQL `pg_trgm` with `word_similarity()` ranking.
- **Semantic Search**: Conceptual search via `pgvector` cosine distance on 768-dimensional embeddings.
- **Auto-Suggestions**: Real-time type-ahead suggestions from contacts and subjects as you type.

### Authentication & Security
- **Google OAuth 2.0**: Single Sign-On via Google with Authorization Code flow.
- **JWT Tokens**: Access token (in-memory) + Refresh token (persistent) with automatic rotation.
- **Concurrency Handling**: `@Lock(PESSIMISTIC_WRITE)` on refresh token operations to prevent race conditions.
- **AES Encryption**: Sensitive credentials (Gmail OAuth tokens) are encrypted at rest via `EncryptionService`.

### Real-Time Notifications
- **WebSocket**: `NotificationWebSocketHandler` pushes real-time events (new email, sync complete) to the frontend.
- **Gmail Pub/Sub**: `GmailWatchService` registers a watch on the user's mailbox; incoming notifications trigger an incremental sync.

### Statistics Dashboard
- Aggregated email statistics (total, unread, starred, sent counts) via `EmailStatsService`.

---

## Architecture

The project follows a **Modular Monolith** architecture with clearly separated domain modules:

```mermaid
graph TD
    Client["Client Browser / API Consumer"] --> Controllers["Presentation Layer<br/><i>Controllers, DTOs, Exception Handling</i>"]
    Controllers --> Services["Service Layer<br/><i>Business Logic, Orchestration</i>"]
    Services --> Repository["Repository Layer<br/><i>JPA Repositories, Specifications</i>"]

    subgraph Infrastructure["Infrastructure & External Services"]
        DB["PostgreSQL 17<br/><i>pgvector, pg_trgm, Flyway</i>"]
        Security["Spring Security<br/><i>JWT, OAuth 2.0</i>"]
        Gmail["Gmail API<br/><i>IMAP, SMTP, Watch, Pub/Sub</i>"]
        AI["AI Services<br/><i>Gemini API, ONNX Runtime</i>"]
        WS["WebSocket<br/><i>Real-time Notifications</i>"]
    end

    Repository -.-> DB
    Services -.-> Security
    Services -.-> Gmail
    Services -.-> AI
    Services -.-> WS
```

```
src/main/java/com/awad/emailclientai/
├── modules/
│   ├── auth/           # Authentication — OAuth 2.0, JWT, refresh tokens
│   │   ├── controller/
│   │   ├── service/
│   │   ├── entity/
│   │   └── repository/
│   ├── email/          # Core email operations — sync, search, AI, stats
│   │   ├── controller/     # EmailController, SearchController, LegacyDashboardController
│   │   ├── service/        # ImapService, SmtpService, AiService, SearchService, CompositeEmbeddingService
│   │   ├── entity/
│   │   └── repository/
│   ├── kanban/         # Kanban board — columns, drag-and-drop, Gmail label mapping
│   │   ├── controller/
│   │   ├── service/
│   │   ├── entity/
│   │   └── repository/
│   └── user/           # User profile management
│       ├── controller/
│       ├── service/
│       └── repository/
├── shared/             # Cross-cutting — security config, CORS, WebSocket, encryption
└── EmailClientAiApplication.java
```

---

## Database Schema

MailBoard uses **PostgreSQL 17** with the `pgvector` and `pg_trgm` extensions for semantic and fuzzy search capabilities.

```mermaid
erDiagram
    USERS ||--o{ EMAIL_ACCOUNTS : owns
    USERS ||--o{ REFRESH_TOKENS : has
    EMAIL_ACCOUNTS ||--o{ EMAILS : syncs
    EMAILS ||--o{ EMAIL_ATTACHMENTS : contains
    KANBAN_COLUMNS }o--|| USERS : "belongs to"

    USERS {
        bigint id PK
        string email
        string name
        string password_hash
        string role
    }

    EMAIL_ACCOUNTS {
        bigint id PK
        bigint user_id FK
        string email_address
        string provider
        string encrypted_access_token
        string encrypted_refresh_token
        bigint last_synced_uid
    }

    EMAILS {
        bigint id PK
        bigint account_id FK
        string message_id UK
        string gmail_message_id
        string thread_id
        string subject
        string sender
        text body
        string status
        double kanban_order
        timestamp received_date
        timestamp snoozed_until
        text summary
        string summary_source
        boolean is_read
        boolean is_starred
        boolean has_attachments
        vector embedding_768
        vector embedding_384
    }

    EMAIL_ATTACHMENTS {
        bigint id PK
        bigint email_id FK
        string filename
        string content_type
        bigint size
        string server_attachment_id
        string content_id
        string external_url
        boolean inline
    }

    KANBAN_COLUMNS {
        bigint id PK
        bigint user_id FK
        string name
        string color
        string gmail_label_id
        integer display_order
    }

    REFRESH_TOKENS {
        bigint id PK
        string token UK
        bigint user_id FK
        timestamp expires_at
        boolean revoked
    }
```

- **Vector Indexes**: HNSW indexes on `embedding_768` and `embedding_384` for sub-second semantic search.
- **Trigram Indexes**: GIN trigram indexes on `subject` and `sender` for fast fuzzy text matching.
- **Migrations**: 7 Flyway migrations managing schema evolution from extensions to email attachments.

---

## Tech Stack

| Category | Technology |
| :--- | :--- |
| **Language** | Java 21 |
| **Framework** | Spring Boot 3.5.8 |
| **Security** | Spring Security + JWT (jjwt 0.13.0) + Google OAuth 2.0 |
| **Database** | PostgreSQL 17 (pgvector + pg_trgm) |
| **ORM** | Spring Data JPA / Hibernate |
| **DB Migration** | Flyway |
| **Object Mapping** | MapStruct 1.6.3 + Lombok |
| **Email** | Eclipse Angus Mail (IMAP + SMTP via XOAUTH2) |
| **AI - Cloud** | Google Gemini API (Summarization + Embeddings) |
| **AI - Local** | ONNX Runtime 1.18.0 + DJL Tokenizers |
| **Vector Search** | pgvector 0.1.6 (Cosine Distance, HNSW) |
| **Real-Time** | Spring WebSocket |
| **Push Notifications** | Google Cloud Pub/Sub (Gmail Watch API) |
| **API Docs** | SpringDoc OpenAPI 2.8 (Swagger UI) |
| **Containerization** | Docker (multi-stage build) + Docker Compose |
| **Deployment** | Render (backend) + Vercel (frontend) |

---

## API Overview

Base path: `/api/v1`

| Group | Prefix | Description |
| :--- | :--- | :--- |
| **Authentication** | `/auth` | Google OAuth login, token refresh, logout, profile |
| **Mailboxes** | `/mailboxes` | List mailboxes (labels), get emails by mailbox with pagination |
| **Emails** | `/emails` | Get detail, search, send, reply, modify labels, sync, download attachments |
| **Search** | `/search` | Fuzzy search, semantic search, auto-suggestions, embedding generation |
| **Kanban** | `/kanban` | CRUD columns, update email status, snooze/unsnooze |
| **Statistics** | `/stats` | Aggregated email statistics (total, unread, starred, sent) |
| **Gmail Pub/Sub** | `/gmail/pubsub` | Webhook receiver for Gmail push notifications |
| **Dashboard** | `/dashboard` | Legacy compatibility endpoints for the frontend |

Interactive API documentation is available at:
```
http://localhost:8080/swagger-ui/index.html
```

---

## Getting Started

### Prerequisites
- **Java 21+**
- **Maven 3.9+**
- **Docker & Docker Compose** (for PostgreSQL with pgvector)

### Option 1: Full Infrastructure Bundle (Recommended)

Spin up PostgreSQL (with pgvector), ngrok (for Gmail Pub/Sub), and the Spring Boot application together:

```bash
# 1. Clone the repository
git clone https://github.com/tlavu2004/mailboard-backend.git
cd mailboard-backend

# 2. Configure environment
cp .env.example .env
# Edit .env and fill in required values (see Environment Variables section)

# 3. Start everything
docker compose up -d
```

The app will be available at `http://localhost:8080`.

### Option 2: Local Development (Database in Docker)

Run only PostgreSQL in Docker, and the application natively:

```bash
# 1. Start database (pgvector-enabled PostgreSQL)
docker compose up -d postgresql

# 2. Configure environment
cp .env.example .env

# 3. Run the application
mvn spring-boot:run
```

---

## Environment Variables

Copy `.env.example` to `.env` and fill in the required values.

> [!IMPORTANT]
> Always ensure your `.env` file is in the root directory before running the application or Docker.

### Required

| Variable | Description |
| :--- | :--- |
| `DB_URL` | PostgreSQL JDBC URL (default: `jdbc:postgresql://localhost:5432/mailboard`) |
| `DB_USERNAME` | Database username (default: `postgres`) |
| `DB_PASSWORD` | Database password (default: `postgres`) |
| `JWT_SECRET` | Secret key for signing JWT tokens. Generate with `openssl rand -base64 32`. |
| `GOOGLE_CLIENT_ID` | Google OAuth client ID from [Google Cloud Console](https://console.cloud.google.com/) |
| `GOOGLE_CLIENT_SECRET` | Google OAuth client secret |
| `ENCRYPTION_AES_KEY` | AES-256 key for encrypting OAuth tokens at rest. Generate with `openssl rand -base64 32`. |

### Optional

| Variable | Description | Default |
| :--- | :--- | :--- |
| `SPRING_PROFILES_ACTIVE` | Active Spring profile | `dev` |
| `SERVER_PORT` | Server port | `8080` |
| `JWT_ACCESS_EXPIRATION` | Access token TTL (ms) | `86400000` (24h) |
| `JWT_REFRESH_EXPIRATION` | Refresh token TTL (ms) | `604800000` (7 days) |
| `CORS_ALLOWED_ORIGIN_1` | Frontend URL for CORS | `http://localhost` |
| `CORS_ALLOWED_ORIGIN_2` | Additional CORS origin | `http://localhost:3000` |

### AI & Search

| Variable | Description | Default |
| :--- | :--- | :--- |
| `GEMINI_API_KEY` | Google Gemini API key(s), comma-separated for rotation | — |
| `GEMINI_EMBEDDING_MODEL` | Embedding model name | `gemini-embedding-001` |
| `GEMINI_CHAT_MODEL` | Chat/summarization model name | `gemini-2.5-flash` |
| `APP_EMBEDDING_LOCAL_ENABLED` | Enable local ONNX fallback | `true` |

### Gmail Push Notifications

| Variable | Description | Default |
| :--- | :--- | :--- |
| `GMAIL_PUBSUB_TOPIC` | Google Cloud Pub/Sub topic | `projects/{PROJECT_ID}/topics/gmail-notifications` |
| `NGROK_AUTHTOKEN` | Ngrok auth token for local Pub/Sub webhook tunnel | — |

---

## Deployment

### Backend — Render

The project includes a `render.yaml` Blueprint and a multi-stage `Dockerfile` optimized for Render's free tier (512MB RAM):

```dockerfile
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-XX:ActiveProcessorCount=1", "-jar", "app.jar"]
```

Set `SPRING_PROFILES_ACTIVE=prod` and configure all required environment variables in Render's dashboard. The production profile automatically disables the local ONNX model to conserve memory.

### Frontend — Vercel

The frontend repository includes a `vercel.json` for deployment to Vercel. See the [frontend README](https://github.com/tlavu2004/mailboard-frontend) for details.

---

## Authors

| Student ID | Full Name | Github |
| :--- | :--- | :--- |
| **22120303** | Mai Xuân Quý | [m-xuanquy](https://github.com/m-xuanquy) |
| **22120430** | Lê Hoàng Việt | [Keruedu](https://github.com/Keruedu) |
| **22120443** | Trương Lê Anh Vũ | [tlavu2004](https://github.com/tlavu2004) |

---

## Contribution

1. Fork the repository.
2. Create a feature branch (`git checkout -b feature/AmazingFeature`).
3. Commit your changes (`git commit -m 'Add some AmazingFeature'`).
4. Push to the branch (`git push origin feature/AmazingFeature`).
5. Open a Pull Request.

---

## License

This project is licensed under the [MIT License](LICENSE).
