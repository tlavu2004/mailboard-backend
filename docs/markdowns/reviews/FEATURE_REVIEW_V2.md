# Feature Review v2 — Backend Self-Assessment

> **Nguồn:** [`SELF_ASSESSMENT_REPORT.md`](file:///d:/Coding/Working/Projects/MailBoard/Sources/mailboard-backend/docs/markdowns/assignments/SELF_ASSESSMENT_REPORT.md)
> **Ngày review:** 2026-02-16
> **Phạm vi:** Backend (`mailboard-backend`) — chỉ đánh giá source code thực tế
> **So sánh với:** [`FEATURE_REVIEW_V1.md`](file:///d:/Coding/Working/Projects/MailBoard/Sources/mailboard-backend/docs/markdowns/reviews/FEATURE_REVIEW_V1.md) (2026-02-13)

---

## Ký hiệu

| Icon | Ý nghĩa |
|------|---------|
| ✅ | Đã triển khai đầy đủ |
| ⚠️ | Triển khai một phần / khác spec |
| ❌ | Chưa triển khai |
| 🆕 | Thay đổi so với V1 |

---

## 1. Overall Requirements

| Feature | Điểm | V1 | V2 | Ghi chú |
|---------|-------|----|----|---------|
| User-centered design | -5 | ⚠️ | ⚠️ | Backend cung cấp đầy đủ API cho Kanban, AI, search. Đánh giá UX chủ yếu thuộc frontend |
| Database design | -1 | ✅ | ✅ | Có đủ bảng: `users`, `emails` (có vector columns), `kanban_columns`, `email_accounts`, `refresh_tokens`. pgvector + pg_trgm extensions |
| Website layout | -2 | ⚠️ | ⚠️ | Thuộc Frontend — Backend chỉ cung cấp API |
| Website architect | -3 | ✅ | ✅ | Spring Boot backend rõ ràng, tách module (`auth`, `email`, `kanban`, `user`). OAuth2 flow, JWT token handling |
| Website stability | -4 | ⚠️ | ⚠️ | Thuộc Frontend — Backend cung cấp REST API chuẩn |
| Document | -2 | ⚠️ | ⚠️ | Có thư mục `docs/` với `guides/`, `markdowns/`, `images/` — cần kiểm tra mức độ đầy đủ |
| Publish to public hosts | -1 | ❌ | ✅ 🆕 | Có `Dockerfile` (multi-stage build) + `docker-compose.yml` + CI/CD push image lên `ghcr.io` |
| Dev progress in Github | -7 | ⚠️ | ⚠️ | Có `.git` — cần kiểm tra chất lượng commit history, branches, PRs |

---

## 2. Authentication & Token Management

| Feature | Điểm | V1 | V2 | Ghi chú |
|---------|-------|----|----|---------|
| Google OAuth 2.0 integration | -0.5 | ✅ | ✅ | `AuthController.googleLogin()` + `GoogleAuthService` |
| Authorization Code flow | -0.5 | ✅ | ✅ | Backend exchange code for tokens qua `GoogleLoginRequest` |
| Token storage & security | -0.5 | ✅ | ✅ | `RefreshToken` entity stored server-side, JWT access token returned to client |
| Automatic token refresh | -0.5 | ✅ | ✅ | `AuthController.refreshToken()` + `RefreshTokenService` |
| Concurrency handling | -0.25 | ⚠️ | ✅ 🆕 | `findByTokenWithLock()` sử dụng `@Lock(LockModeType.PESSIMISTIC_WRITE)` — serialized access khi concurrent refresh requests |
| Forced logout on invalid refresh | -0.25 | ⚠️ | ✅ 🆕 | `rotateRefreshToken()` kiểm tra `isExpired()`, xoá token + throw `TokenRefreshException`. `deleteExpiredTokens()` dọn dẹp định kỳ |
| Logout & token cleanup | -0.25 | ✅ | ✅ | `AuthController.logout()` xóa refresh token qua `deleteByUserId()` |

---

## 3. Email Synchronization & Display

| Feature | Điểm | V1 | V2 | Ghi chú |
|---------|-------|----|----|---------|
| Fetch emails from Gmail | -0.5 | ✅ | ✅ | `ImapService.getMessages()` + `EmailSyncService.syncEmailsForAccount()` — dùng IMAP |
| Email list with pagination | -0.25 | ✅ | ✅ | `ImapService.getMessages(account, folder, page, size)` hỗ trợ phân trang |
| Email detail view | -0.25 | ✅ | ✅ | `ImapService.getMessageDetail()` trả về full body + attachments |
| Mailbox/Labels list | -0.25 | ✅ | ✅ | `ImapService.getFolders()` liệt kê các folder/label |
| Open in Gmail link | -0.25 | ❌ | ✅ 🆕 | `EmailController` tạo link `https://mail.google.com/mail/u/0/#search/rfc822msgid:{messageId}`, trả về qua `EmailEntityDto.gmailLink` |

---

## 4. Kanban Board Interface

| Feature | Điểm | V1 | V2 | Ghi chú |
|---------|-------|----|----|---------|
| Kanban board layout | -0.5 | ⚠️ | ⚠️ | Thuộc Frontend — Backend cung cấp API columns + email status |
| Email cards display | -0.25 | ✅ | ✅ | `EmailEntityDto` trả về sender, subject, snippet, summary |
| Drag-and-drop between columns | -0.5 | ⚠️ | ⚠️ | Thuộc Frontend — Backend có `EmailController.updateStatus()` |
| Status persistence | -0.25 | ✅ | ✅ | `EmailEntity.status` (INBOX, TODO, DONE, SNOOZED) lưu DB |
| Settings interface | -0.25 | ⚠️ | ⚠️ | Thuộc Frontend — Backend có CRUD API cho columns |
| Configuration persistence | -0.25 | ✅ | ✅ | `KanbanColumn` entity lưu DB: `name`, `position`, `linkedStatus`, `gmailLabelId` |
| Gmail label mapping | -0.5 | ❌ | ✅ 🆕 | `KanbanColumn.gmailLabelId` + `EmailSyncService.determineStatusFromLabels()` auto-mapping label → column. `findOrCreateColumn()` tự tạo column mới khi gặp label lạ |

---

## 5. Snooze Mechanism

| Feature | Điểm | V1 | V2 | Ghi chú |
|---------|-------|----|----|---------|
| Select snooze time | -0.25 | ✅ | ✅ | `EmailController.snoozeEmail(id, until)` với `LocalDateTime` |
| Hide snoozed emails | -0.25 | ✅ | ✅ | Email có status `SNOOZED`, filter bằng `EmailSpecification` |
| Auto-return on schedule | -0.5 | ✅ | ✅ | `EmailSyncService.checkSnoozedEmails()` chạy `@Scheduled(fixedRate = 10000)` |

---

## 6. AI Features

| Feature | Điểm | V1 | V2 | Ghi chú |
|---------|-------|----|----|---------|
| Backend summarization API | -0.5 | ✅ | ✅ | `AiService.summarizeEmail()` — Gemini API + extractive fallback |
| Summary UI on cards | -0.25 | ✅ | ✅ | `EmailEntity.summary` trả về qua `EmailEntityDto.summary` |
| Embedding generation | -0.5 | ✅ | ✅ | `CompositeEmbeddingService` — Gemini (768-dim) + ONNX local fallback (384-dim) |
| Vector database storage | -0.5 | ✅ | ✅ | pgvector: `embedding_768` và `embedding_384` columns |

---

## 7. Search Features

### Fuzzy Search (Backend)

| Feature | Điểm | V1 | V2 | Ghi chú |
|---------|-------|----|----|---------|
| Typo tolerance | -0.5 | ❌ | ✅ 🆕 | Nâng cấp từ `LIKE` lên `word_similarity()` (pg_trgm). Migration `V9__add_pg_trgm_fuzzy_search.sql` thêm extension + GIN trigram indexes |
| Partial matches | -0.5 | ✅ | ✅ | Kết hợp `word_similarity() > 0.3` VÀ `LIKE '%query%'` fallback |
| Relevance ranking | -0.25 | ❌ | ✅ 🆕 | `GREATEST(word_similarity(:query, e.subject), word_similarity(:query, e.sender))` để ranking. `SearchResultDto` trả về `relevanceScore` |

### Fuzzy Search UI (Frontend)

| Feature | Điểm | V1 | V2 | Ghi chú |
|---------|-------|----|----|---------|
| Search bar integration | -0.25 | ⚠️ | ⚠️ | Thuộc Frontend — Backend có `GET /api/v1/emails/search` |
| Search results as cards | -0.25 | ⚠️ | ⚠️ | Thuộc Frontend — Backend trả về `List<EmailEntityDto>` |
| Loading/empty/error states | -0.25 | ⚠️ | ⚠️ | Thuộc Frontend |
| Navigation back to main | -0.25 | ⚠️ | ⚠️ | Thuộc Frontend |

### Semantic Search

| Feature | Điểm | V1 | V2 | Ghi chú |
|---------|-------|----|----|---------|
| Conceptual relevance search | -0.5 | ✅ | ✅ | `SearchService.semanticSearch()` — cosine distance (`<=>`) |
| Semantic search API endpoint | -0.25 | ✅ | ✅ | `POST /api/v1/search/semantic` |

### Search Auto-Suggestion

| Feature | Điểm | V1 | V2 | Ghi chú |
|---------|-------|----|----|---------|
| Type-ahead dropdown | -0.25 | ⚠️ | ⚠️ | Thuộc Frontend — Backend có `GET /api/v1/search/suggestions` |
| Suggestions from contacts/keywords | -0.25 | ✅ | ✅ | `findSuggestions()` dùng `similarity()` + `%` operator từ pg_trgm |
| Trigger search on selection | -0.25 | ⚠️ | ⚠️ | Thuộc Frontend |

---

## 8. Filtering & Sorting

| Feature | Điểm | V1 | V2 | Ghi chú |
|---------|-------|----|----|---------|
| Sort by date (newest/oldest) | -0.25 | ✅ | ✅ | `EmailController.getEmails()` với sort `receivedDate,desc/asc` |
| Filter by unread | -0.25 | ✅ | ✅ | `EmailSpecification.filterEmails()` — `isRead = false` |
| Filter by attachments | -0.25 | ✅ | ✅ | `EmailSpecification.filterEmails()` — `hasAttachments = true` |
| Real-time filter updates | -0.25 | ⚠️ | ⚠️ | Thuộc Frontend — Backend trả kết quả filtered ngay lập tức |

---

## 9. Email Actions

| Feature | Điểm | V1 | V2 | Ghi chú |
|---------|-------|----|----|---------|
| Mark as read/unread | -0.25 | ✅ | ✅ | `ImapService.setMessageRead()` + controller endpoint |
| Compose modal | -0.25 | ⚠️ | ⚠️ | Thuộc Frontend — Backend có `sendEmail()` API |
| Reply/Forward flow | -0.25 | ✅ | ✅ | `SendEmailRequestDto` hỗ trợ `replyToMessageId`, `inReplyTo`, `references` |
| Send via Gmail API | -0.25 | ✅ | ✅ | `SmtpService.sendEmail()` qua SMTP OAuth2 XOAUTH2 |
| View attachments | -0.25 | ✅ | ✅ | `ImapService.convertToDetailDto()` liệt kê attachments |
| Download attachments | -0.25 | ✅ | ✅ | `ImapService.downloadAttachment()` + controller endpoint |
| Delete emails | -0.25 | ✅ | ✅ | `ImapService.deleteMessage()` — move to Trash |

---

## 10. Advanced Features

| Feature | Điểm | V1 | V2 | Ghi chú |
|---------|-------|----|----|---------|
| Gmail Push Notifications | +0.25 | ❌ | ❌ | Không có Gmail Watch, Pub/Sub, hoặc WebSocket. Kiến trúc hiện tại là **Modulithic** (single Spring Boot process, không cần Redis) — chỉ cần WebSocket (backend → frontend) + Gmail Watch API + Pub/Sub (Gmail → backend) |
| Multi-tab logout sync | +0.25 | ⚠️ | ⚠️ | Thuộc Frontend (BroadcastChannel) |
| Offline caching | +0.25 | ⚠️ | ⚠️ | Thuộc Frontend (IndexedDB) |
| Keyboard navigation | +0.25 | ⚠️ | ⚠️ | Thuộc Frontend |
| Dockerize your project | +0.25 | ❌ | ✅ 🆕 | `Dockerfile` (multi-stage: maven build → JRE runtime) + `docker-compose.yml` + `.dockerignore` |
| CI/CD | +0.25 | ❌ | ✅ 🆕 | `.github/workflows/ci.yml`: Build + Test (Maven + pgvector service) → Docker build → Push to `ghcr.io` |

---

## Tổng hợp thay đổi V1 → V2

### 🆕 Đã sửa/bổ sung từ V1

| # | Feature | V1 | V2 | Chi tiết |
|---|---------|----|----|----------|
| 1 | Concurrency handling | ⚠️ | ✅ | `@Lock(PESSIMISTIC_WRITE)` trên `findByTokenWithLock()` |
| 2 | Forced logout on invalid refresh | ⚠️ | ✅ | `rotateRefreshToken()` xoá expired token + throw `TokenRefreshException` |
| 3 | Gmail label mapping | ❌ | ✅ | `KanbanColumn.gmailLabelId` + `determineStatusFromLabels()` + `findOrCreateColumn()` |
| 4 | Fuzzy search typo tolerance | ❌ | ✅ | `word_similarity()` từ pg_trgm, GIN trigram indexes |
| 5 | Relevance ranking | ❌ | ✅ | `GREATEST(word_similarity())` + `SearchResultDto.relevanceScore` |
| 6 | Dockerize | ❌ | ✅ | `Dockerfile` + `docker-compose.yml` + `.dockerignore` |
| 7 | CI/CD | ❌ | ✅ | GitHub Actions workflow: build, test, Docker push to ghcr.io |
| 8 | Open in Gmail link | ❌ | ✅ | `EmailEntityDto.gmailLink` |

### ❌ Vẫn chưa triển khai (Backend)

| # | Feature | Ghi chú |
|---|---------|---------|
| 1 | Gmail Push Notifications | Không có Gmail Watch API / Pub/Sub / WebSocket. Kiến trúc Modulithic → không cần Redis, chỉ cần WebSocket + Gmail Watch |

### ⚠️ Thuộc Frontend (không đánh giá từ backend)

- Website layout, stability, compatibility
- Kanban board layout, drag-and-drop, settings UI
- Search bar UI, search results display, loading/error states
- Compose modal, keyboard navigation, multi-tab logout, offline caching
- Real-time filter updates

---

## Kết luận

**Tiến triển từ V1 → V2:** Đã sửa **8 mục ❌/⚠️** quan trọng nhất từ V1. Backend hiện tại cover hầu hết các feature cốt lõi trong spec.

**Mục ❌ duy nhất còn lại:** Gmail Push Notifications — cần Gmail Watch API + WebSocket.

**Kiến trúc:** Dự án sử dụng **Modulithic** (single Spring Boot process, modules: `auth`, `email`, `kanban`, `user`). Không có Redis, không có Spring Modulith dependency — tất cả giao tiếp nội bộ qua method call trực tiếp. Với kiến trúc này, Push Notifications chỉ cần thêm WebSocket (Spring WebSocket) + Gmail Watch API, **không cần Redis**.

**Đánh giá tổng quan:** Backend đã hoàn thành **~98%** các feature liên quan. Feature duy nhất còn lại là Gmail Push Notifications (advanced/bonus feature).
