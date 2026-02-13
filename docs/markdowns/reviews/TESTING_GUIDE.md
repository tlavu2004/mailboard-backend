# Testing Guide — Feature Implementation

> Cập nhật theo từng task trên branch `feature/advanced-features`

---

## Task 1.1 — pg_trgm Extension & Trigram Indexes

**Commit:** `feat(search): add pg_trgm extension and trigram indexes`

**File thay đổi:**
- `src/main/resources/db/migration/V9__add_pg_trgm_fuzzy_search.sql` (new)

**Cách test:**

1. **Khởi động ứng dụng** để Flyway chạy migration V9:
   ```bash
   ./mvnw spring-boot:run
   ```

2. **Kiểm tra extension đã được cài** — kết nối PostgreSQL và chạy:
   ```sql
   SELECT * FROM pg_extension WHERE extname = 'pg_trgm';
   ```
   → Phải thấy `pg_trgm` trong kết quả.

3. **Kiểm tra indexes đã được tạo:**
   ```sql
   SELECT indexname, indexdef 
   FROM pg_indexes 
   WHERE tablename = 'emails' 
     AND indexname LIKE '%trgm%';
   ```
   → Phải thấy 2 indexes: `idx_emails_subject_trgm` và `idx_emails_sender_trgm`.

4. **Test nhanh trigram similarity** (chỉ cần có data trong bảng emails):
   ```sql
   -- Typo tolerance: "marketng" tìm "marketing"
   SELECT subject, similarity(subject, 'marketng') AS score
   FROM emails
   WHERE subject % 'marketng'
   ORDER BY score DESC
   LIMIT 5;
   ```

**Kết quả mong đợi:** Migration chạy thành công, extension và indexes được tạo, query `similarity()` hoạt động.

---

## Task 1.2 — Trigram-based Fuzzy Search with Scoring

**Commit:** `feat(search): implement trigram-based fuzzy search with scoring`

**File thay đổi:**
- `src/main/java/.../repository/EmailRepository.java` (modified)

**Cách test:**

1. **Gọi API search với từ khóa đúng:**
   ```
   GET /api/v1/emails/search?accountId=1&q=marketing
   ```
   → Trả về email có subject/sender chứa "marketing"

2. **Test typo tolerance:**
   ```
   GET /api/v1/emails/search?accountId=1&q=marketng
   ```
   → Vẫn trả về email "marketing" nhờ trigram similarity

3. **Test partial match vẫn hoạt động:**
   ```
   GET /api/v1/emails/search?accountId=1&q=Nguy
   ```
   → Trả về email từ "Nguyen Van A"

4. **Kết quả được sắp xếp:** Email match cao nhất nằm đầu (ORDER BY similarity DESC)

---

## Task 1.3 — Relevance Ranking in Search Results

**Commit:** `feat(search): add relevance ranking to search results`

**File thay đổi:**
- `SearchResultDto.java` (new) — DTO chứa email + relevanceScore
- `EmailRepository.java` — thêm `searchEmailsWithScore()` trả `Object[]` với score
- `EmailController.java` — cập nhật `searchEmails()` trả `SearchResultDto`

**Cách test:**

1. **Gọi API search:**
   ```
   GET /api/v1/emails/search?accountId=1&q=test
   ```

2. **Kiểm tra response format mới:**
   ```json
   {
     "data": [
       {
         "email": { "id": 1, "subject": "Test email", ... },
         "relevanceScore": 0.75
       },
       {
         "email": { "id": 2, "subject": "Testing 123", ... },
         "relevanceScore": 0.45
       }
     ]
   }
   ```
   → Mỗi kết quả có `email` + `relevanceScore` (0.0–1.0), sắp xếp giảm dần.

---

## Task 1.4 — Suggestions with Trigram Similarity

**Commit:** `feat(search): improve suggestions with trigram similarity`

**File thay đổi:**
- `EmailRepository.java` — cập nhật `findSuggestions()` dùng trigram

**Cách test:**

1. **Gọi API suggestions:**
   ```
   GET /api/v1/search/suggestions?q=gogle
   ```
   → Trả về "Google" nhờ trigram similarity (typo tolerance)

2. **Test partial match:**
   ```
   GET /api/v1/search/suggestions?q=Nguy
   ```
   → Trả về "Nguyen Van A" (vẫn hoạt động)

3. **Kết quả sắp xếp theo relevance:** Suggestions có similarity cao nhất nằm đầu
