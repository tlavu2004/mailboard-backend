# Hướng dẫn Kỹ thuật & Test API Kanban Workflow (GA05)

Dưới đây là các bước để kiểm tra chức năng **Kanban Board**, **Snooze**, và **AI Summarization** trong MailBoard (GA05).

> [!IMPORTANT]
> **Yêu cầu:** 
> 1. Bạn cần link ít nhất 1 email account (xem GA04 Guide).
> 2. Cần cấu hình `OPENAI_API_KEY` trong file `.env` để test AI Summarization (Priority 1).
> 3. Cần start Ollama (`mailboard-ollama` container hoặc local) cho Priority 2 (Optional).

## 1. Cấu hình Variables (Collection Level)

Tiếp tục sử dụng các variable từ phần GA04. Đảm bảo bạn đã có:
- `base_url`: `http://localhost:8080/api/v1`
- `access_token`: (Login để lấy)
- `account_id`: (ID của account đã kết nối)

---

## 2. Kanban Workflow

### A. Đồng bộ Email (Sync from IMAP)
Để đưa email từ IMAP (Gmail/Outlook) vào database cục bộ của MailBoard cho Kanban, bạn cần gọi API Sync.

- **Method**: `POST`
- **URL**: `{{base_url}}/emails/sync?accountId={{account_id}}&limit=50&folderName=INBOX`
- **Auth**: Inherit (Bearer Token)
- **Params (Optional)**:
  - `limit`: Số lượng email muốn sync (Mặc định: 50).
  - `folderName`: Thư mục muốn sync (Mặc định: `INBOX`). Ví dụ: `[Gmail]/Sent Mail`.
- **Kỳ vọng**: 
  - Status `200 OK`.
  - Body: `{"success": true, "message": "Sync completed"}`.
  - **Tác dụng**: Hệ thống sẽ tải số lượng email mới nhất (theo limit) từ thư mục chỉ định về và lưu vào bảng `emails` với status mặc định `INBOX` (để hiện lên bảng Kanban).

### B. Lấy danh sách Email (Kanban Board)
API này trả về danh sách email đã lưu trong DB, dùng để render lên các cột Kanban.

- **Method**: `GET`
- **URL**: `{{base_url}}/emails?accountId={{account_id}}`
- **Auth**: Inherit
- **Params (Optional)**: 
  - `status`: Lọc theo cột (VD: `INBOX`, `TODO`, `DONE`, `SNOOZED`).
  - Ví dụ: `{{base_url}}/emails?accountId=1&status=TODO`
- **Kỳ vọng**: 
  - Status `200 OK`.
  - Trả về danh sách EmailEntityDto (gồm `id`, `subject`, `snippet`, `status`, `summary`...).

### C. Kéo thả Card (Update Status)
Di chuyển email giữa các cột (VD: Từ Inbox sang To Do).

- **Method**: `PUT`
- **URL**: `{{base_url}}/emails/{{email_id}}/status?status=TODO`
- **Auth**: Inherit
- **Params**:
  - `status`: `INBOX`, `TODO`, `IN_PROGRESS`, `DONE`, `SNOOZED`.
- **Kỳ vọng**: 
  - Status `200 OK`.
  - Card được cập nhật trạng thái mới.

---

## 3. Chức năng Snooze (Tạm hoãn)

Email bị Snooze sẽ chuyển sang trạng thái `SNOOZED` và bị ẩn khỏi quy trình làm việc cho đến thời điểm được chọn.

- **Method**: `PUT`
- **URL**: `{{base_url}}/emails/{{email_id}}/snooze?until=2026-02-04T10:00:00`
- **Auth**: Inherit
- **Params**:
  - `until`: Thời gian email sẽ "thức dậy" (ISO 8601 Format: `yyyy-MM-dd'T'HH:mm:ss`).
- **Kỳ vọng**: 
  - Status `200 OK`.
  - Email chuyển sang status `SNOOZED`.
  - Sau thời gian `until`, background job sẽ tự động chuyển nó về `INBOX`.

---

## 4. Test Kịch bản (Scenario)

1. **Sync**: Gọi API Sync (2.A) để lấy dữ liệu.
2. **View**: Gọi API Get (2.B) để thấy email ở trạng thái `INBOX`.
3. **Move**: Gọi API Status (2.C) chuyển 1 email sang `TODO`. Kiểm tra lại bằng API Get.
4. **Snooze**: Gọi API Snooze (3) với thời gian tương lai gần (VD: 1 phút nữa).
   - Kiểm tra status là `SNOOZED`.
   - Chờ 1 phút -> Gọi API Get -> Kiểm tra status quay về `INBOX`.

---

## Troubleshooting

- **Lỗi 500 khi Sync?**
  - Kiểm tra kết nối IMAP (App Password, Account ID đúng chưa).
