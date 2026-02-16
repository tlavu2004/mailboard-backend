# Feature Review v1 — Backend Self-Assessment

> **Nguồn:** [`SELF_ASSESSMENT_REPORT.md`](file:///d:/Coding/Working/Projects/MailBoard/Sources/mailboard-backend/docs/markdowns/assignments/SELF_ASSESSMENT_REPORT.md)
> **Ngày review:** 2026-02-13
> **Phạm vi:** Backend (`mailboard-backend`) — chỉ đánh giá source code thực tế

---

## Ký hiệu

| Icon | Ý nghĩa |
|------|---------|
| ✅ | Đã triển khai đầy đủ |
| ⚠️ | Triển khai một phần / khác spec |
| ❌ | Chưa triển khai |

---

## 1. Overall Requirements

| Feature | Điểm | Trạng thái | Ghi chú |
|---------|-------|------------|---------|
| User-centered design | -5 | ⚠️ | Backend cung cấp đầy đủ API cho Kanban, AI, search. Đánh giá UX chủ yếu thuộc frontend |
| Database design | -1 | ✅ | Có đủ bảng: `users`, `emails` (có vector columns), `kanban_columns`, `email_accounts`, `refresh_tokens`. Sử dụng pgvector |
| Database mock data | -1 | ⚠️ | Không thấy file seed data / migration chứa mock data rõ ràng trong source |
| Website layout | -2 | ⚠️ | Thuộc Frontend — Backend chỉ cung cấp API |
| Website architect | -3 | ✅ | Spring Boot backend rõ ràng, tách module (auth, email, kanban, user). OAuth2 flow, JWT token handling |
| Website stability and compatibility | -4 | ⚠️ | Thuộc Frontend — Backend cung cấp REST API chuẩn |
| Document | -2 | ⚠️ | Có docs folder với guides và markdowns, cần kiểm tra mức độ đầy đủ |
| Demo video | -5 | ❌ | Không tìm thấy demo video trong repo backend |
| Publish to public hosts | -1 | ❌ | Không có Dockerfile, docker-compose, hoặc deployment config trong repo |
| Development progress recorded in Github | -7 | ⚠️ | Có `.git` — cần kiểm tra chất lượng commit history, branches, PRs |

---

## 2. Authentication & Token Management

| Feature | Điểm | Trạng thái | Ghi chú |
|---------|-------|------------|---------|
| Google OAuth 2.0 integration | -0.5 | ✅ | `AuthController.googleLogin()` + `GoogleAuthService` |
| Authorization Code flow | -0.5 | ✅ | Backend exchange code for tokens qua `GoogleLoginRequest` |
| Token storage & security | -0.5 | ✅ | `RefreshToken` entity stored server-side, JWT access token returned to client |
| Automatic token refresh | -0.5 | ✅ | `AuthController.refreshToken()` + `RefreshTokenService` |
| Concurrency handling | -0.25 | ⚠️ | Không tìm thấy cơ chế lock/synchronized cho concurrent refresh requests |
| Forced logout on invalid refresh | -0.25 | ⚠️ | Cần kiểm tra logic khi refresh token hết hạn/bị thu hồi |
| Logout & token cleanup | -0.25 | ✅ | `AuthController.logout()` xóa refresh token |

---

## 3. Email Synchronization & Display

| Feature | Điểm | Trạng thái | Ghi chú |
|---------|-------|------------|---------|
| Fetch emails from Gmail | -0.5 | ✅ | `ImapService.getMessages()` + `EmailSyncService.syncEmailsForAccount()` — dùng IMAP (không phải Gmail REST API) |
| Email list with pagination | -0.25 | ✅ | `ImapService.getMessages(account, folder, page, size)` hỗ trợ phân trang |
| Email detail view | -0.25 | ✅ | `ImapService.getMessageDetail()` trả về full body + attachments |
| Mailbox/Labels list | -0.25 | ✅ | `ImapService.getFolders()` liệt kê các folder/label |
| Open in Gmail link | -0.25 | ❌ | Không có logic tạo Gmail web link trong backend |

---

## 4. Kanban Board Interface

| Feature | Điểm | Trạng thái | Ghi chú |
|---------|-------|------------|---------|
| Kanban board layout | -0.5 | ⚠️ | Thuộc Frontend — Backend cung cấp API columns + email status |
| Email cards display | -0.25 | ✅ | `EmailEntityDto` trả về sender, subject, snippet, summary |
| Drag-and-drop between columns | -0.5 | ⚠️ | Thuộc Frontend — Backend có `EmailController.updateStatus()` để thay đổi status |
| Status persistence | -0.25 | ✅ | `EmailEntity.status` (INBOX, TODO, DONE, SNOOZED) lưu DB |
| Settings interface | -0.25 | ⚠️ | Thuộc Frontend — Backend có CRUD API cho columns |
| Configuration persistence | -0.25 | ✅ | `KanbanColumn` entity lưu DB với name, position, linkedStatus |
| Gmail label mapping | -0.5 | ❌ | `KanbanColumn` có field `linkedStatus` nhưng không có logic sync với Gmail labels. Không có code mapping column ↔ Gmail label |

---

## 5. Snooze Mechanism

| Feature | Điểm | Trạng thái | Ghi chú |
|---------|-------|------------|---------|
| Select snooze time | -0.25 | ✅ | `EmailController.snoozeEmail(id, until)` với `LocalDateTime` parameter |
| Hide snoozed emails | -0.25 | ✅ | Email có status `SNOOZED`, filter bằng `EmailSpecification` |
| Auto-return on schedule | -0.5 | ✅ | `EmailSyncService.checkSnoozedEmails()` chạy `@Scheduled(fixedRate = 10000)` — tự động trả email về INBOX |

---

## 6. AI Features

| Feature | Điểm | Trạng thái | Ghi chú |
|---------|-------|------------|---------|
| Backend summarization API | -0.5 | ✅ | `AiService.summarizeEmail()` — Gemini API + extractive fallback algorithm |
| Summary UI on cards | -0.25 | ✅ | `EmailEntity.summary` field được lưu và trả về qua `EmailEntityDto.summary` |
| Embedding generation | -0.5 | ✅ | `CompositeEmbeddingService` — Gemini (768-dim) + ONNX local fallback (384-dim) |
| Vector database storage | -0.5 | ✅ | pgvector trong PostgreSQL: `embedding_768` và `embedding_384` columns. `EmailRepository.updateEmbedding768/384()` |

---

## 7. Search Features

### Fuzzy Search (Backend)

| Feature | Điểm | Trạng thái | Ghi chú |
|---------|-------|------------|---------|
| Typo tolerance | -0.5 | ❌ | Dùng `LIKE '%query%'` — **không hỗ trợ typo tolerance**. Cần pg_trgm hoặc Levenshtein |
| Partial matches | -0.5 | ✅ | `LIKE '%query%'` hỗ trợ partial match trên subject và sender |
| Relevance ranking | -0.25 | ❌ | Không có scoring/ranking — trả về tất cả kết quả match không theo thứ tự relevance |

### Fuzzy Search UI (Frontend)

| Feature | Điểm | Trạng thái | Ghi chú |
|---------|-------|------------|---------|
| Search bar integration | -0.25 | ⚠️ | Thuộc Frontend — Backend có endpoint `GET /api/v1/emails/search` |
| Search results as cards | -0.25 | ⚠️ | Thuộc Frontend — Backend trả về `List<EmailEntityDto>` |
| Loading/empty/error states | -0.25 | ⚠️ | Thuộc Frontend |
| Navigation back to main view | -0.25 | ⚠️ | Thuộc Frontend |

### Semantic Search

| Feature | Điểm | Trạng thái | Ghi chú |
|---------|-------|------------|---------|
| Conceptual relevance search | -0.5 | ✅ | `SearchService.semanticSearch()` — vector similarity với cosine distance (`<=>`) |
| Semantic search API endpoint | -0.25 | ✅ | `POST /api/v1/search/semantic` endpoint tồn tại |

### Search Auto-Suggestion

| Feature | Điểm | Trạng thái | Ghi chú |
|---------|-------|------------|---------|
| Type-ahead dropdown | -0.25 | ⚠️ | Thuộc Frontend — Backend có `GET /api/v1/search/suggestions` |
| Suggestions from contacts/keywords | -0.25 | ✅ | `EmailRepository.findSuggestions()` — lấy từ `subject` và `sender`, LIMIT 10 |
| Trigger search on selection | -0.25 | ⚠️ | Thuộc Frontend |

---

## 8. Filtering & Sorting

| Feature | Điểm | Trạng thái | Ghi chú |
|---------|-------|------------|---------|
| Sort by date (newest/oldest) | -0.25 | ✅ | `EmailController.getEmails()` với sort param `receivedDate,desc/asc` |
| Filter by unread | -0.25 | ✅ | `EmailSpecification.filterEmails()` — filter `isRead = false` |
| Filter by attachments | -0.25 | ✅ | `EmailSpecification.filterEmails()` — filter `hasAttachments = true` |
| Real-time filter updates | -0.25 | ⚠️ | Thuộc Frontend — Backend trả kết quả filtered ngay lập tức |

---

## 9. Email Actions

| Feature | Điểm | Trạng thái | Ghi chú |
|---------|-------|------------|---------|
| Mark as read/unread | -0.25 | ✅ | `ImapService.setMessageRead()` + `EmailAccountController.setMessageRead()` |
| Compose modal | -0.25 | ⚠️ | Thuộc Frontend — Backend có `sendEmail()` API |
| Reply/Forward flow | -0.25 | ✅ | `SendEmailRequestDto` hỗ trợ `replyToMessageId`, `inReplyTo`, `references` fields |
| Send via Gmail API | -0.25 | ✅ | `SmtpService.sendEmail()` — gửi qua SMTP (hỗ trợ OAuth2 XOAUTH2) + lưu vào Sent folder qua IMAP |
| View attachments | -0.25 | ✅ | `ImapService.convertToDetailDto()` liệt kê attachments |
| Download attachments | -0.25 | ✅ | `ImapService.downloadAttachment()` + `EmailAccountController.downloadAttachment()` |
| Delete emails | -0.25 | ✅ | `ImapService.deleteMessage()` — move to Trash |

---

## 10. Advanced Features

| Feature | Điểm | Trạng thái | Ghi chú |
|---------|-------|------------|---------|
| Gmail Push Notifications | +0.25 | ❌ | Không có Gmail watch, Pub/Sub, hoặc WebSocket trong codebase |
| Multi-tab logout sync | +0.25 | ⚠️ | Thuộc Frontend (BroadcastChannel) |
| Offline caching | +0.25 | ⚠️ | Thuộc Frontend (IndexedDB) |
| Keyboard navigation | +0.25 | ⚠️ | Thuộc Frontend |
| Dockerize your project | +0.25 | ❌ | Không có Dockerfile hoặc docker-compose trong repo backend |
| CI/CD | +0.25 | ❌ | Không có GitHub Actions, Jenkins, hoặc CI/CD config |

---

## Tóm tắt (Backend)

### ✅ Đã hoàn thành tốt
- Authentication & Token Management (Google OAuth, JWT, refresh token)
- Email Sync via IMAP (fetch, paginate, detail)
- Kanban API (CRUD columns, status updates)
- Snooze (set time + scheduled auto-return)
- AI Summarization (Gemini + extractive fallback)
- Vector Embeddings (Gemini 768 + ONNX 384 + pgvector)
- Semantic Search (cosine distance similarity)
- Email Actions (send, reply/forward, attachments, read/star/delete)
- Filtering & Sorting (unread, attachments, date)
- Auto-suggestions (subject + sender prefix)

### ❌ Chưa triển khai
- **Fuzzy search typo tolerance** (cần pg_trgm / Levenshtein)
- **Relevance ranking** cho fuzzy search
- **Gmail label mapping** cho Kanban columns
- **Open in Gmail link** (tạo weblink)
- **Dockerfile / docker-compose**
- **CI/CD pipeline**
- **Gmail Push Notifications** (Pub/Sub)
- **Demo video**

### ⚠️ Cần chú ý
- Nhiều feature thuộc Frontend (layout, drag-drop, UI states) — không đánh giá được từ backend
- Fuzzy search hiện tại chỉ là `LIKE '%query%'`, không đúng spec "typo tolerance"
- Concurrency handling cho token refresh chưa rõ
- Mock data chưa thấy rõ ràng
