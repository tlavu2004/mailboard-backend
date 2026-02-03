# Hướng dẫn Test API Email Accounts với Postman (GA04)

Dưới đây là các bước để bạn kiểm tra chức năng quản lý Email Accounts (IMAP/SMTP) trong MailBoard.

> [!IMPORTANT]
> **Restricted Social Mode:** Chức năng Link Account chỉ hoạt động với user đăng nhập bằng **Email/Password (Local)**. Nếu bạn đăng nhập bằng Google, endpoint `/connect` sẽ trả về **403 Forbidden**.

## 1. Cấu hình Variables (Collection Level)

Tương tự như phần Auth, hãy đảm bảo các biến sau được set trong **Collection Variables**:

| Variable | Initial Value | Current Value | Mô tả |
| :--- | :--- | :--- | :--- |
| `base_url` | `http://localhost:8080/api/v1` | `http://localhost:8080/api/v1` | URL gốc của API |
| `access_token` | *(Để trống)* | *(Tự động điền sau login)* | Token xác thực JWT |
| `account_id` | *(Để trống)* | *(Tự động điền sau khi connect)* | ID của tài khoản email liên kết |

> **Lưu ý:** Trước khi test các API này, bạn **PHẢI** chạy API **Local Login** (Email/Password) để lấy `access_token`. Google Login không được phép link account.

---

## 1.5. Hướng dẫn lấy App Password (Quan trọng)

Để kết nối được Gmail/Outlook qua giao thức SMTP/IMAP Basic Auth, bạn **KHÔNG THỂ** dùng mật khẩu đăng nhập bình thường. Bạn bắt buộc phải tạo **App Password**.

### Đối với Gmail:
1.  Truy cập [Google My Account](https://myaccount.google.com/).
2.  Vào mục **Security** -> Bật **2-Step Verification** (nếu chưa bật).
3.  Tìm kiếm "App passwords" trên thanh tìm kiếm của trang (hoặc vào Security -> 2-Step Verification -> Cuối trang chọn App passwords).
4.  Điền App name: `MailBoard`.
5.  Bấm **Create**.
6.  Copy chuỗi ký tự 16 chữ số (ví dụ: `xxxx xxxx xxxx xxxx`) -> Đây chính là `password` để dùng trong API Connect.

### Đối với Outlook:
1.  Truy cập [Microsoft Account Security](https://account.live.com/proofs/manage/additional).
2.  Bật **Two-step verification** (nếu chưa bật).
3.  Kéo xuống mục **App passwords** -> Chọn **Create a new app password**.
4.  Copy password được tạo -> Dùng làm `password` kết nối.

> [!WARNING]
> **Lỗi "Không tìm thấy tài khoản Microsoft"?**
> Nếu bạn dùng email sinh viên/công ty (`@...edu.vn`, `@...company.com`), đây là **Work/School Account**, không phải Personal Account.
> *   Những tài khoản này quản lý tại `myaccount.microsoft.com` và thường **bị Admin chặn SMTP/IMAP**.
> *   **Giải pháp:** Hãy dùng tài khoản cá nhân (`@outlook.com`, `@hotmail.com`, `@live.com`) để test tính năng này.

---


## 2. Quản lý Tài khoản (Email Accounts)

### A. Kết nối Tài khoản (Connect)
- **Method**: `POST`
- **URL**: `{{base_url}}/email-accounts/connect`
- **Auth**: Inherit auth from parent
- **Yêu cầu:** User phải đăng nhập bằng **Local Login** (Email/Password). Google Login sẽ bị từ chối.
- **Body** (JSON):
  ```json
  {
    "emailAddress": "your-email@gmail.com",
    "password": "xxxx xxxx xxxx xxxx", 
    "displayName": "My Work Email",
    "provider": "GMAIL",
    "authType": "BASIC"
  }
  ```
  > **Quan trọng:** Với Gmail, `password` phải là **App Password** (16 ký tự), không phải password đăng nhập Google.

- **Kỳ vọng**: Status 200 OK.
- **Mẹo (Tự động lưu Account ID)**:
  - Vào tab **Scripts** -> **Post-response** của request này.
  - Paste đoạn code sau:
  ```javascript
  var jsonData = pm.response.json();
  if (jsonData.success && jsonData.data) {
      pm.collectionVariables.set("account_id", jsonData.data.id);
      console.log("Account ID saved:", jsonData.data.id);
  }
  ```

### B. Xem danh sách Tài khoản
- **Method**: `GET`
- **URL**: `{{base_url}}/email-accounts`
- **Auth**: Inherit auth from parent
- **Kỳ vọng**: Status 200 OK. Danh sách các account đã liên kết.

### C. Ngắt kết nối (Disconnect)
- **Method**: `DELETE`
- **URL**: `{{base_url}}/email-accounts/{{account_id}}`
- **Auth**: Inherit auth from parent
- **Kỳ vọng**: Status 200 OK.

---

## 3. Thao tác với Folders & Messages

### A. Lấy danh sách Folders
- **Method**: `GET`
- **URL**: `{{base_url}}/email-accounts/{{account_id}}/folders`
- **Auth**: Inherit auth from parent
- **Kỳ vọng**: Status 200 OK. Danh sách folders (INBOX, SENT, ...).

### B. Lấy danh sách Email trong Folder
- **Method**: `GET`
- **URL**: `{{base_url}}/email-accounts/{{account_id}}/folders/INBOX/messages?page=0&size=10`
- **Auth**: Inherit auth from parent
- **Kỳ vọng**: Status 200 OK. List headers của email (không có body).
- **Mẹo**: Nhớ lấy `uid` của một email để test chi tiết.

### C. Xem chi tiết Email (Kèm Body)
- **Method**: `GET`
- **URL**: `{{base_url}}/email-accounts/{{account_id}}/folders/INBOX/messages/{{email_uid}}`
- **Auth**: Inherit auth from parent
- **Params**: Thay `{{email_uid}}` bằng UID thực tế.
- **Kỳ vọng**: Status 200 OK. Trả về `bodyText`, `bodyHtml`, `attachments`.

---

## 4. Gửi Email (SMTP)

### Gửi Email (Có hoặc không có file đính kèm)
- **Method**: `POST`
- **URL**: `{{base_url}}/email-accounts/{{account_id}}/send`
- **Auth**: Inherit auth from parent
- **Body**: `form-data`
  
  | Key | Type | Value/Description |
  | :--- | :--- | :--- |
  | `email` | Text | JSON string chứa nội dung email (xem bên dưới) |
  | `attachments` | File | *(Optional)* Chọn file từ máy (có thể chọn nhiều file) |
  
  **Giá trị của key `email`** (dán vào ô Value, chọn Type là Text):
  ```json
  {
    "to": ["recipient@example.com"],
    "subject": "Test from MailBoard",
    "bodyHtml": "<h1>Hello!</h1><p>This is a test email.</p>"
  }
  ```
  
  > **Lưu ý trong Postman:**
  > 1. Chọn tab **Body** → chọn **form-data**
  > 2. Key `email`: chọn Type = **Text**, paste JSON vào ô Value
  > 3. Key `attachments` *(optional)*: chọn Type = **File**, chọn file đính kèm
  > 4. Nếu không muốn đính kèm file, bỏ qua key `attachments`

- **Kỳ vọng**: Status 200 OK. Email được gửi đi thành công.

---

## 5. Thao tác khác

### A. Đánh dấu Đã đọc/Chưa đọc
- **Method**: `PATCH`
- **URL**: `{{base_url}}/email-accounts/{{account_id}}/folders/{{folder_name}}/messages/{{email_uid}}/read`
- **Auth**: Inherit auth from parent
- **Body** (JSON):
  ```json
  {
    "read": true
  }
  ```

### B. Tải file đính kèm
- **Method**: `GET`
- **URL**: `{{base_url}}/email-accounts/{{account_id}}/folders/INBOX/messages/{{email_uid}}/attachments/{{attachment_id}}`
- **Auth**: Inherit auth from parent
- **Lưu ý**: Trong Postman, bấm mũi tên bên cạnh nút "Send" -> chọn "Send and Download" để tải file về.

---

## Lưu ý Debug

1. **Lỗi "Failed to connect to email server"**:
   - Kiểm tra lại **App Password** (Xem hướng dẫn bật 2FA & App Password của Google).
   - Kiểm tra firewall xem có chặn port 993/587 không.

2. **Lỗi 401 Unauthorized**:
   - Token hết hạn? Chạy lại Login hoặc Refresh Token request.

3. **Lỗi 403 Forbidden (EMAIL_009)**:
   - Bạn đang đăng nhập bằng **Google Login**. Chức năng link account chỉ dành cho Local Login.
   - **Giải pháp:** Đăng ký/đăng nhập bằng Email/Password thay vì Google.

