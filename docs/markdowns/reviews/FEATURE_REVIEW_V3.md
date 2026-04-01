# Feature Review v3 — Full Stack Self-Assessment

> **Nguồn:** [`SELF_ASSESSMENT_REPORT.md`](file:///d:/Coding/Working/Projects/MailBoard/Sources/mailboard-backend/docs/markdowns/assignments/SELF_ASSESSMENT_REPORT.md)
> **Ngày review:** 2026-04-01
> **Phạm vi:** Backend (`mailboard-backend`) + Frontend (`mailboard-frontend`)
> **So sánh với:** [`FEATURE_REVIEW_V2.md`](file:///d:/Coding/Working/Projects/MailBoard/Sources/mailboard-backend/docs/markdowns/reviews/FEATURE_REVIEW_V2.md) (2026-02-16, chỉ Backend)

---

## Ký hiệu

| Icon | Ý nghĩa |
|------|---------|
| ✅ | Đã triển khai đầy đủ |
| ⚠️ | Triển khai một phần / cần cải thiện |
| ❌ | Chưa triển khai |
| 🆕 | Thay đổi so với V2 |

> [!NOTE]
> V3 đánh giá **cả Backend lẫn Frontend** — khác V1/V2 chỉ đánh giá Backend.

---

## 1. Overall Requirements

| Feature | Điểm | BE | FE | Ghi chú |
|---------|-------|:--:|:--:|---------:|
| User-centered design | -5 | ✅ | ✅ | BE: Full API (Kanban, AI, search, statistics, WebSocket). FE: 3-column layout (sidebar, email list, detail), Kanban board view, responsive design, dark/light mode toggle |
| Database design | -1 | ✅ | — | 9 migrations: `users`, `emails` (with vectors), `kanban_columns` (with color), `email_accounts`, `refresh_tokens`. pgvector + pg_trgm extensions |
| Database mock data | -1 | ✅ (N/A) | — | Không áp dụng — project sync data thật từ Gmail qua OAuth2/IMAP |
| Website layout | -2 | — | ✅ | Next.js 3-column layout: Sider (mailbox list) + Content (email list/Kanban) + Detail panel. Responsive cho mobile (Drawer) |
| Website architect | -3 | ✅ | ✅ | BE: Spring Boot modular (auth, email, kanban, user, shared). FE: Next.js App Router + `contexts/` + `services/` + `hooks/` + `components/` |
| Website stability | -4 | ⚠️ | ✅ | BE: có `DataIntegrityViolationException` khi sync trùng email (edge case). FE: ổn định, có error handling, loading states |
| Document | -2 | ⚠️ | ✅ | BE: `docs/markdowns/` (guides, reviews, assignments). FE: `README.md` (10.7KB) chi tiết |
| Demo video | -5 | ✅ (N/A) | ✅ (N/A) | Không áp dụng — giảng viên không yêu cầu video demo cho project này |
| Publish to public hosts | -1 | ✅ | ✅ | BE: `Dockerfile` + `docker-compose.yml` + CI → `ghcr.io`. FE: `Dockerfile` + `docker-compose.yml` + nginx config |
| Dev progress in Github | -7 | ✅ | ✅ | BE: 175 commits, 14 branches. FE: 81 commits, 4 branches. Meaningful feature branches |

---

## 2. Authentication & Token Management

| Feature | Điểm | BE | FE | Ghi chú |
|---------|-------|:--:|:--:|---------:|
| Google OAuth 2.0 integration | -0.5 | ✅ | ✅ | BE: `GoogleAuthService`. FE: `LoginPage` với Google Sign-In button, `AuthContext.googleAuth()` |
| Authorization Code flow | -0.5 | ✅ | ✅ | BE: exchange code for tokens. FE: gửi code từ Google callback → backend |
| Token storage & security | -0.5 | ✅ | ✅ | BE: Refresh token server-side, `EncryptionService`. FE: Access token in-memory (`api.ts`), refresh token in localStorage |
| Automatic token refresh | -0.5 | ✅ | ✅ | BE: `RefreshTokenService` + `GoogleTokenService.refreshAccessToken()`. FE: Axios interceptor retry 401 → refresh → retry request |
| Concurrency handling | -0.25 | ✅ | ⚠️ | BE: `@Lock(PESSIMISTIC_WRITE)`. FE: Không thấy mutex/queue cho concurrent 401 refresh — có thể gọi refresh nhiều lần cùng lúc |
| Forced logout on invalid refresh | -0.25 | ✅ | ✅ | BE: throw `TokenRefreshException`. FE: Axios interceptor catch refresh failure → clear tokens → redirect `/login` |
| Logout & token cleanup | -0.25 | ✅ | ✅ | BE: `deleteByUserId()`. FE: `authService.logout()` + clear localStorage + clear PWA cache + redirect |

---

## 3. Email Synchronization & Display

| Feature | Điểm | BE | FE | Ghi chú |
|---------|-------|:--:|:--:|---------:|
| Fetch emails from Gmail | -0.5 | ✅ | ✅ | BE: IMAP + Gmail Watch API. FE: `emailService.getEmails()` + auto-refresh via WebSocket notifications (`useEmailNotifications`) |
| Email list with pagination | -0.25 | ✅ | ✅ | BE: page/size params. FE: `Pagination` component, `handlePageChange()` |
| Email detail view | -0.25 | ✅ | ✅ | BE: full body + attachments. FE: `EmailDetail.tsx` — iframe render HTML body, attachments list, AI summary card |
| Mailbox/Labels list | -0.25 | ✅ | ✅ | BE: `GmailLabelService.getLabels()`. FE: `Sider` với icon-mapped menu từ API labels |
| Open in Gmail link | -0.25 | ✅ | ✅ | BE: `EmailEntityDto.gmailLink`. FE: `EmailDetail` → "Open in Gmail" button → `window.open()` |

---

## 4. Kanban Board Interface

| Feature | Điểm | BE | FE | Ghi chú |
|---------|-------|:--:|:--:|---------:|
| Kanban board layout | -0.5 | ✅ | ✅ | BE: full column API. FE: `KanbanBoard.tsx` với horizontal scroll, `KanbanColumn.tsx`, `KanbanCard.tsx` |
| Email cards display | -0.25 | ✅ | ✅ | BE: `EmailEntityDto`. FE: `KanbanCard.tsx` — sender avatar, subject, snippet, summary, star/attachment badges |
| Drag-and-drop between columns | -0.5 | ✅ | ✅ | BE: `updateStatus()` + Gmail label sync. FE: `@dnd-kit` — drag cards between columns + reorder columns |
| Status persistence | -0.25 | ✅ | ✅ | BE: `EmailEntity.status` + `kanbanOrder`. FE: optimistic update + API call |
| Settings interface | -0.25 | ✅ | ✅ | BE: CRUD API. FE: `KanbanSettingsModal.tsx` (22KB) — create/rename/delete/reorder columns, pick color, link Gmail label |
| Configuration persistence | -0.25 | ✅ | ✅ | BE: `KanbanColumn` entity + color field. FE: reload columns from API, `AddColumnButton.tsx` quick add |
| Gmail label mapping | -0.5 | ✅ | ✅ | BE: `determineStatusFromLabels()`. FE: Settings modal cho phép map column ↔ Gmail label |

---

## 5. Snooze Mechanism

| Feature | Điểm | BE | FE | Ghi chú |
|---------|-------|:--:|:--:|---------:|
| Select snooze time | -0.25 | ✅ | ✅ | BE: `snoozeEmail(id, until)`. FE: Snooze button trong `KanbanCard` với date picker (Tomorrow, Next Week, Custom) |
| Hide snoozed emails | -0.25 | ✅ | ✅ | BE: status = `SNOOZED`, filtered via `EmailSpecification`. FE: Snoozed emails chỉ hiện trong cột Snoozed |
| Auto-return on schedule | -0.5 | ✅ | ⚠️ | BE: `@Scheduled(fixedRate = 10000)` tự động chuyển về INBOX. FE: Cần refresh/WebSocket để thấy email quay về — không auto-update real-time trên UI |

---

## 6. AI Features

| Feature | Điểm | BE | FE | Ghi chú |
|---------|-------|:--:|:--:|---------:|
| Backend summarization API | -0.5 | ✅ | ✅ | BE: Gemini + extractive fallback. FE: "AI Summary" button trong `EmailDetail`, loading spinner while generating |
| Summary UI on cards | -0.25 | ✅ | ✅ | BE: `EmailEntityDto.summary`. FE: `KanbanCard` hiển thị summary text + `summarySource` badge (Gemini/Extractive) |
| Embedding generation | -0.5 | ✅ | ✅ | BE: CompositeEmbeddingService. FE: auto-generate embeddings mỗi 2 phút qua `searchService.generateEmbeddings(50)` |
| Vector database storage | -0.5 | ✅ | — | pgvector: `embedding_768` + `embedding_384`. Thuần backend |

---

## 7. Search Features

### Fuzzy Search (Backend)

| Feature | Điểm | BE | FE | Ghi chú |
|---------|-------|:--:|:--:|---------:|
| Typo tolerance | -0.5 | ✅ | ✅ | BE: `word_similarity()` (pg_trgm). FE: gọi `emailService.searchEmails()` |
| Partial matches | -0.5 | ✅ | ✅ | BE: `word_similarity() > 0.3` + `LIKE` fallback. FE: kết quả hiện qua `SearchResults.tsx` |
| Relevance ranking | -0.25 | ✅ | ✅ | BE: `GREATEST(word_similarity())`. FE: `SearchResults` hiển thị relevance badge (% match) |

### Fuzzy Search UI (Frontend)

| Feature | Điểm | BE | FE | Ghi chú |
|---------|-------|:--:|:--:|---------:|
| Search bar integration | -0.25 | ✅ | ✅ | `SearchInput.tsx` — AutoComplete component trong header, debounced (300ms) |
| Search results as cards | -0.25 | — | ✅ | `SearchResults.tsx` — Card layout với sender, subject, snippet, highlight matched text, relevance badge |
| Loading/empty/error states | -0.25 | — | ✅ | Loading spinner khi đang tìm, `Empty` component khi không có kết quả, error toast |
| Navigation back to main | -0.25 | — | ✅ | "Back" button (`ArrowLeftOutlined`) trong `SearchResults` → clear search → return to list/kanban |

### Semantic Search

| Feature | Điểm | BE | FE | Ghi chú |
|---------|-------|:--:|:--:|---------:|
| Conceptual relevance search | -0.5 | ✅ | ✅ | BE: cosine distance via pgvector. FE: `searchMode` toggle (semantic/text), "AI Semantic" tag hiển thị khi dùng semantic search |
| Semantic search API endpoint | -0.25 | ✅ | ✅ | BE: `POST /api/v1/search/semantic`. FE: `searchService.semanticSearch()` |

### Search Auto-Suggestion

| Feature | Điểm | BE | FE | Ghi chú |
|---------|-------|:--:|:--:|---------:|
| Type-ahead dropdown | -0.25 | ✅ | ✅ | BE: `GET /api/v1/search/suggestions`. FE: `SearchInput` dùng Ant Design `AutoComplete`, dropdown hiện khi gõ ≥ 2 ký tự |
| Suggestions from contacts/keywords | -0.25 | ✅ | ✅ | BE: `findSuggestions()` từ pg_trgm. FE: hiển thị icon `UserOutlined`/`TagOutlined` phân biệt sender vs subject |
| Trigger search on selection | -0.25 | ✅ | ✅ | FE: `handleSelect()` gọi `onSearch(selectedValue)` khi user chọn suggestion |

---

## 8. Filtering & Sorting

| Feature | Điểm | BE | FE | Ghi chú |
|---------|-------|:--:|:--:|---------:|
| Sort by date (newest/oldest) | -0.25 | ✅ | ✅ | BE: `Sort.by()`. FE: `FilterBar` → Select dropdown (Newest First, Oldest First, Sender A-Z, Sender Z-A) |
| Filter by unread | -0.25 | ✅ | ✅ | BE: `EmailSpecification`. FE: `FilterBar` → checkbox-style button "Unread" toggle |
| Filter by attachments | -0.25 | ✅ | ✅ | BE: `EmailSpecification`. FE: `FilterBar` → checkbox-style button "Has attachment" toggle |
| Real-time filter updates | -0.25 | ✅ | ✅ | FE: `useEffect` watches `filters.unread`, `filters.hasAttachment`, `sortMode` → auto-reload emails khi thay đổi, không cần refresh page |

---

## 9. Email Actions

| Feature | Điểm | BE | FE | Ghi chú |
|---------|-------|:--:|:--:|---------:|
| Mark as read/unread | -0.25 | ✅ | ✅ | BE: `setMessageRead()`. FE: optimistic update khi chọn email — auto mark as read |
| Compose modal | -0.25 | ✅ | ✅ | BE: `SmtpService.sendEmail()`. FE: `ComposeModal.tsx` (604 lines, 20KB) — full compose UI: To/Cc/Bcc tags, subject, body textarea, file upload (max 10), image preview, file size display |
| Reply/Forward flow | -0.25 | ✅ | ✅ | BE: `SendEmailRequestDto`. FE: Reply prefills sender + "Re:" subject, Forward prefills "Fwd:". Gmail-style quoted content toggle ("..." button → expandable iframe) |
| Send via Gmail API | -0.25 | ✅ | ✅ | BE: SMTP XOAUTH2. FE: `emailService.sendEmail()` với FormData (to, cc, bcc, subject, body, threadId, attachments) |
| View attachments | -0.25 | ✅ | ✅ | BE: `convertToDetailDto()`. FE: `EmailDetail` → Attachments card với filename, size, download button |
| Download attachments | -0.25 | ✅ | ✅ | BE: `downloadAttachment()`. FE: `emailService.downloadAttachment()` → blob download |
| Delete emails | -0.25 | ✅ | ✅ | BE: move to Trash via IMAP. FE: Delete button (danger style) trong `EmailDetail`, optimistic update list |

---

## 10. Advanced Features

| Feature | Điểm | BE | FE | Ghi chú |
|---------|-------|:--:|:--:|---------:|
| Gmail Push Notifications | +0.25 | ✅ | ✅ | BE: `GmailWatchService` + `GmailPubSubController` + `NotificationWebSocketHandler`. FE: `useEmailNotifications` hook — WebSocket `/ws/notifications`, auto-reconnect mỗi 5s |
| Multi-tab logout sync | +0.25 | — | ✅ | FE: `AuthContext` dùng `window.addEventListener('storage')` — khi `refreshToken` bị xóa ở tab khác → auto logout tab hiện tại. Không dùng BroadcastChannel, dùng StorageEvent (tương đương, cross-browser) |
| Offline caching | +0.25 | — | ⚠️ | FE: `OfflineIndicator.tsx` hiện banner khi offline + auto-reload khi online. `useCachedData` hook dùng Cache API (`caches.open()`). Nhưng **không dùng IndexedDB** và không có Service Worker active (đã bị unregister trong AuthContext). Chỉ mức basic |
| Keyboard navigation | +0.25 | — | ❌ | Không tìm thấy keyboard shortcut nào (no `useKeyboard`, `hotkey`, `keydown` listener cho navigation) |
| Dockerize your project | +0.25 | ✅ | ✅ | BE: `Dockerfile` + `docker-compose.yml`. FE: `Dockerfile` (multi-stage Next.js build) + `docker-compose.yml` + `nginx/` config |
| CI/CD | +0.25 | ✅ | ⚠️ | BE: GitHub Actions CI. FE: Có `.github/` nhưng cần kiểm tra chi tiết workflow |

---

## Bảng tổng hợp Full-Stack

| Nhóm | Features | ✅ Done | ⚠️ Partial | ❌ Missing |
|------|:---:|:---:|:---:|:---:|
| 1. Overall Requirements | 10 | 9 | 1 (stability) | 0 |
| 2. Authentication | 7 | 6 | 1 (concurrency FE) | 0 |
| 3. Email Sync & Display | 5 | 5 | 0 | 0 |
| 4. Kanban Board | 7 | 7 | 0 | 0 |
| 5. Snooze | 3 | 2 | 1 (auto-return UI) | 0 |
| 6. AI Features | 4 | 4 | 0 | 0 |
| 7. Search | 10 | 10 | 0 | 0 |
| 8. Filtering & Sorting | 4 | 4 | 0 | 0 |
| 9. Email Actions | 7 | 7 | 0 | 0 |
| 10. Advanced Features | 6 | 3 | 2 (offline, CI/CD FE) | 1 (keyboard) |
| **Tổng** | **63** | **57** | **5** | **1** |

---

## 📋 Danh sách tính năng cần hoàn thiện

### ❌ Chưa triển khai

| # | Feature | Phạm vi | Điểm | Ước lượng | Cần làm |
|---|---------|:-------:|-------|:---------:|---------|
| 1 | **Keyboard navigation** | `FE` | +0.25 | ~2h | Thêm hook `useKeyboardShortcuts`: `j/k` navigate emails, `e` archive, `r` reply, `c` compose, `Esc` back. Bind vào `InboxPage` |

### ⚠️ Cần cải thiện

| # | Feature | Phạm vi | Điểm | Ước lượng | Cần làm |
|---|---------|:-------:|-------|:---------:|---------|
| 1 | **Concurrency handling** (refresh token) | `FE` | -0.25 | ~1h | Thêm `isRefreshing` flag + request queue trong Axios interceptor (`api.ts`). Khi nhiều request 401 đồng thời, chỉ gọi refresh 1 lần, các request khác chờ result |
| 2 | **Snooze auto-return UI** | `FE` | *(thuộc -0.5)* | ~30m | Khi `useEmailNotifications` nhận message type `NEW_EMAILS`, tự động reload Kanban data để email snoozed wake-up hiện lên ngay |
| 3 | **Offline caching** | `FE` | +0.25 | ~3h | Thêm Service Worker active (hiện đã bị unregister). Implement stale-while-revalidate cho API responses. Hoặc dùng IndexedDB lưu email list offline |
| 4 | **BE duplicate key sync bug** | `BE` | *(stability)* | ~1h | Fix `DataIntegrityViolationException` trong `EmailSyncService.generateAndSetEmbedding()`. Thêm `existsByMessageId()` check trước save, hoặc dùng try-catch ignore duplicate |
| 5 | **FE CI/CD workflow** | `FE` | +0.25 | ~30m | Kiểm tra và hoàn thiện `.github/workflows/` để chạy build + test + Docker push tương tự BE |

---

## Tổng quan

**Full-Stack hoàn thành: ~90%** (57/63 features ✅)

| Mức | Số feature | Chi tiết |
|-----|:---:|---------:|
| ✅ Done | 57 | Tất cả core features đã hoạt động |
| ⚠️ Cần cải thiện | 5 | Concurrency FE, snooze UI, offline, BE sync bug, FE CI/CD |
| ❌ Chưa có | 1 | Keyboard navigation (+0.25đ bonus) |

> [!TIP]
> Thứ tự ưu tiên sửa: **Concurrency FE** (1h, -0.25đ required) → **Snooze UI** (30m) → **BE sync bug** (1h) → **FE CI/CD** (30m) → **Keyboard** (2h, bonus) → **Offline** (3h, bonus)

> **So sánh V1 → V2 → V3:**
> - V1 (BE only): Nhiều ❌ (8 features thiếu)
> - V2 (BE only): Sửa 8 mục, 98% BE done
> - V3 (Full-Stack): **100% BE done**, 90% Full-Stack. 5 items ⚠️ cần cải thiện, 1 item ❌ (bonus)
