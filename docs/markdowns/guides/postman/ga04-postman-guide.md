# GA04 - Postman Testing Guide

Hướng dẫn test các API endpoints cho Email Accounts (IMAP/SMTP integration).

## Thiết lập môi trường

### 1. Tạo Environment trong Postman

Tạo environment mới với các variables:

| Variable | Initial Value | Description |
|----------|---------------|-------------|
| `base_url` | `http://localhost:8080` | Backend URL |
| `access_token` | (empty) | JWT token sau khi login |
| `account_id` | (empty) | Email account ID sau khi connect |

### 2. Chuẩn bị Gmail App Password

> **Quan trọng:** Gmail yêu cầu App Password nếu bật 2FA

1. Truy cập https://myaccount.google.com/apppasswords
2. Chọn **Mail** → **Windows Computer** (hoặc Other)
3. Click **Generate** → Copy 16-ký tự password
4. Lưu password này để dùng trong bước connect

---

## API Endpoints

### Authentication (Lấy Token)

#### Login
```http
POST {{base_url}}/api/v1/auth/login
Content-Type: application/json

{
    "email": "your-app-email@example.com",
    "password": "your-app-password"
}
```

**Response:**
```json
{
    "success": true,
    "data": {
        "accessToken": "eyJhbGciOiJIUzI1NiIs...",
        "refreshToken": "...",
        "expiresIn": 3600
    }
}
```

> **Auto-save token:** Thêm script vào Tests tab:
> ```javascript
> var jsonData = pm.response.json();
> pm.environment.set("access_token", jsonData.data.accessToken);
> ```

---

### Email Account Management

#### 1. Connect Email Account
```http
POST {{base_url}}/api/v1/email-accounts/connect
Authorization: Bearer {{access_token}}
Content-Type: application/json

{
    "emailAddress": "your-gmail@gmail.com",
    "password": "xxxx xxxx xxxx xxxx",  // Gmail App Password
    "provider": "GMAIL",
    "authType": "BASIC",
    "displayName": "My Gmail Account"
}
```

**Response (200 OK):**
```json
{
    "success": true,
    "message": "Email account connected successfully",
    "data": {
        "id": 1,
        "emailAddress": "your-gmail@gmail.com",
        "displayName": "My Gmail Account",
        "provider": "GMAIL",
        "authType": "BASIC",
        "imapHost": "imap.gmail.com",
        "imapPort": 993,
        "smtpHost": "smtp.gmail.com",
        "smtpPort": 587,
        "active": true,
        "lastSyncAt": "2026-01-31T12:00:00"
    }
}
```

> **Auto-save account_id:**
> ```javascript
> var jsonData = pm.response.json();
> pm.environment.set("account_id", jsonData.data.id);
> ```

#### 2. List Connected Accounts
```http
GET {{base_url}}/api/v1/email-accounts
Authorization: Bearer {{access_token}}
```

#### 3. Get Account Details
```http
GET {{base_url}}/api/v1/email-accounts/{{account_id}}
Authorization: Bearer {{access_token}}
```

#### 4. Disconnect Account
```http
DELETE {{base_url}}/api/v1/email-accounts/{{account_id}}
Authorization: Bearer {{access_token}}
```

---

### Folder Operations

#### List Folders
```http
GET {{base_url}}/api/v1/email-accounts/{{account_id}}/folders
Authorization: Bearer {{access_token}}
```

**Response:**
```json
{
    "success": true,
    "data": [
        {
            "name": "INBOX",
            "displayName": "Inbox",
            "messageCount": 150,
            "unreadCount": 5,
            "type": "INBOX"
        },
        {
            "name": "[Gmail]/Sent Mail",
            "displayName": "Sent Mail",
            "messageCount": 45,
            "unreadCount": 0,
            "type": "SENT"
        }
    ]
}
```

---

### Message Operations

#### 1. List Messages (Paginated)
```http
GET {{base_url}}/api/v1/email-accounts/{{account_id}}/folders/INBOX/messages?page=0&size=20
Authorization: Bearer {{access_token}}
```

**Response:**
```json
{
    "success": true,
    "data": [
        {
            "uid": 12345,
            "messageId": "<abc123@mail.gmail.com>",
            "from": "sender@example.com",
            "fromName": "John Doe",
            "to": ["you@gmail.com"],
            "subject": "Hello World",
            "sentAt": "2026-01-31T10:30:00",
            "read": false,
            "starred": false,
            "hasAttachments": true
        }
    ]
}
```

#### 2. Get Message Details
```http
GET {{base_url}}/api/v1/email-accounts/{{account_id}}/folders/INBOX/messages/12345
Authorization: Bearer {{access_token}}
```

**Response:**
```json
{
    "success": true,
    "data": {
        "uid": 12345,
        "subject": "Hello World",
        "from": "sender@example.com",
        "bodyText": "Plain text content...",
        "bodyHtml": "<html>...</html>",
        "attachments": [
            {
                "id": "0",
                "filename": "document.pdf",
                "contentType": "application/pdf",
                "size": 102400
            }
        ]
    }
}
```

#### 3. Mark as Read/Unread
```http
PATCH {{base_url}}/api/v1/email-accounts/{{account_id}}/folders/INBOX/messages/12345/read?read=true
Authorization: Bearer {{access_token}}
```

#### 4. Star/Unstar Message
```http
PATCH {{base_url}}/api/v1/email-accounts/{{account_id}}/folders/INBOX/messages/12345/star?starred=true
Authorization: Bearer {{access_token}}
```

#### 5. Delete Message
```http
DELETE {{base_url}}/api/v1/email-accounts/{{account_id}}/folders/INBOX/messages/12345
Authorization: Bearer {{access_token}}
```

---

### Send Email

```http
POST {{base_url}}/api/v1/email-accounts/{{account_id}}/send
Authorization: Bearer {{access_token}}
Content-Type: application/json

{
    "to": ["recipient@example.com"],
    "cc": [],
    "bcc": [],
    "subject": "Test Email from MailBoard",
    "bodyText": "This is a plain text email.",
    "bodyHtml": "<h1>Hello!</h1><p>This is an HTML email.</p>",
    "isHtml": true
}
```

**Response:**
```json
{
    "success": true,
    "message": "Email sent successfully",
    "data": "<message-id@mail.gmail.com>"
}
```

---

### 📎 Download Attachment

```http
GET {{base_url}}/api/v1/email-accounts/{{account_id}}/folders/INBOX/messages/12345/attachments/0
Authorization: Bearer {{access_token}}
```

> Response là binary file. Trong Postman, chọn **Send and Download** để save file.

---

## Test Scenarios

### Happy Path

| # | Test | Expected |
|---|------|----------|
| 1 | Login | 200 OK, access_token returned |
| 2 | Connect Gmail | 200 OK, account created |
| 3 | List Folders | 200 OK, INBOX visible |
| 4 | List Messages | 200 OK, emails returned |
| 5 | Get Message Detail | 200 OK, body content returned |
| 6 | Send Email | 200 OK, email sent |
| 7 | Disconnect | 200 OK, account removed |

### Error Cases

| # | Test | Expected |
|---|------|----------|
| 1 | Connect với wrong password | 400 Bad Request, `EMAIL_003` |
| 2 | Access account không thuộc user | 404 Not Found, `EMAIL_002` |
| 3 | Request không có token | 401 Unauthorized |

---

## Troubleshooting

### "Failed to connect to email server"
- Kiểm tra App Password (không phải password thường)
- Gmail: Bật "Less secure apps" hoặc dùng App Password
- Kiểm tra IMAP đã bật trong Gmail Settings

### "Authentication failed"
- Kiểm tra username là full email address
- Gmail: Phải dùng App Password nếu có 2FA

### "Connection timeout"
- Kiểm tra firewall không block port 993/587
- Kiểm tra network connectivity

---

## Provider Settings Reference

| Provider | IMAP Host | Port | SMTP Host | Port |
|----------|-----------|------|-----------|------|
| Gmail | imap.gmail.com | 993 | smtp.gmail.com | 587 |
| Outlook | outlook.office365.com | 993 | smtp.office365.com | 587 |
| Yahoo | imap.mail.yahoo.com | 993 | smtp.mail.yahoo.com | 587 |
