# GA03 - Postman Testing Guide

Hướng dẫn test các API endpoints cho Authentication (Email/Password + Google Sign-In).

## Thiết lập môi trường

### 1. Tạo Environment trong Postman

| Variable | Initial Value | Description |
|----------|---------------|-------------|
| `base_url` | `http://localhost:8080` | Backend URL |
| `access_token` | (empty) | JWT access token sau login |
| `refresh_token` | (empty) | Refresh token sau login |

---

## API Endpoints

### 🔐 Authentication

---

#### 1. Register (Đăng ký tài khoản mới)

```http
POST {{base_url}}/api/v1/auth/register
Content-Type: application/json

{
    "email": "newuser@example.com",
    "password": "Password123!",
    "name": "New User"
}
```

**Success Response (200 OK):**
```json
{
    "success": true,
    "message": "User registered successfully. Please login."
}
```

**Error - Email exists (409 Conflict):**
```json
{
    "success": false,
    "errorCode": "BUSINESS_001",
    "message": "An account with this email already exists"
}
```

---

#### 2. Login (Email + Password)

```http
POST {{base_url}}/api/v1/auth/login
Content-Type: application/json

{
    "email": "user@example.com",
    "password": "Password123!"
}
```

**Success Response (200 OK):**
```json
{
    "success": true,
    "data": {
        "accessToken": "eyJhbGciOiJIUzI1NiIs...",
        "refreshToken": "a1b2c3d4-e5f6-...",
        "tokenType": "Bearer",
        "expiresIn": 3600
    }
}
```

> 💡 **Auto-save tokens:** Thêm script vào Tests tab:
> ```javascript
> var jsonData = pm.response.json();
> if (jsonData.success) {
>     pm.environment.set("access_token", jsonData.data.accessToken);
>     pm.environment.set("refresh_token", jsonData.data.refreshToken);
> }
> ```

**Error - Invalid credentials (401 Unauthorized):**
```json
{
    "success": false,
    "errorCode": "AUTH_001",
    "message": "Invalid email or password"
}
```

---

#### 3. Google Sign-In

```http
POST {{base_url}}/api/v1/auth/google
Content-Type: application/json

{
    "idToken": "eyJhbGciOiJSUzI1NiIs..."
}
```

> **Lấy Google ID Token:**
> 1. Dùng [Google OAuth Playground](https://developers.google.com/oauthplayground/)
> 2. Chọn "Google OAuth2 API v2" → "userinfo.email" + "userinfo.profile"
> 3. Exchange authorization code → Copy `id_token`

**Success Response (200 OK):**
```json
{
    "success": true,
    "data": {
        "accessToken": "eyJhbGciOiJIUzI1NiIs...",
        "refreshToken": "a1b2c3d4-e5f6-...",
        "tokenType": "Bearer",
        "expiresIn": 3600
    }
}
```

---

#### 4. Refresh Token

```http
POST {{base_url}}/api/v1/auth/refresh
Content-Type: application/json

{
    "refreshToken": "{{refresh_token}}"
}
```

**Success Response (200 OK):**
```json
{
    "success": true,
    "data": {
        "accessToken": "eyJhbGciOiJIUzI1NiIs...",
        "refreshToken": "new-refresh-token-...",
        "tokenType": "Bearer",
        "expiresIn": 3600
    }
}
```

**Error - Expired/Invalid (401 Unauthorized):**
```json
{
    "success": false,
    "errorCode": "AUTH_004",
    "message": "Refresh token expired. Please login again"
}
```

---

#### 5. Logout

```http
POST {{base_url}}/api/v1/auth/logout
Content-Type: application/json

{
    "refreshToken": "{{refresh_token}}"
}
```

**Success Response (200 OK):**
```json
{
    "success": true,
    "message": "Logged out successfully"
}
```

---

### 📧 Mock Email API (Protected)

> **Yêu cầu:** Phải đính kèm `Authorization: Bearer {{access_token}}`

---

#### 1. List Mailboxes

```http
GET {{base_url}}/api/v1/mailboxes
Authorization: Bearer {{access_token}}
```

**Response:**
```json
{
    "success": true,
    "data": [
        { "id": 1, "name": "Inbox", "type": "INBOX", "unreadCount": 5 },
        { "id": 2, "name": "Sent", "type": "SENT", "unreadCount": 0 },
        { "id": 3, "name": "Drafts", "type": "DRAFTS", "unreadCount": 2 }
    ]
}
```

---

#### 2. Seed Dummy Data

```http
POST {{base_url}}/api/v1/mailboxes/seed
Authorization: Bearer {{access_token}}
```

**Response:**
```json
{
    "success": true,
    "data": "Dummy data seeded successfully"
}
```

---

#### 3. List Emails in Mailbox

```http
GET {{base_url}}/api/v1/mailboxes/1/emails?page=0&size=20
Authorization: Bearer {{access_token}}
```

**Response:**
```json
{
    "success": true,
    "data": {
        "content": [
            {
                "id": 1,
                "from": "sender@example.com",
                "subject": "Welcome!",
                "preview": "Thank you for signing up...",
                "receivedAt": "2026-01-31T10:00:00",
                "read": false,
                "starred": true
            }
        ],
        "totalElements": 50,
        "totalPages": 3
    }
}
```

---

#### 4. Get Email Detail

```http
GET {{base_url}}/api/v1/emails/1
Authorization: Bearer {{access_token}}
```

**Response:**
```json
{
    "success": true,
    "data": {
        "id": 1,
        "from": "sender@example.com",
        "to": ["you@example.com"],
        "subject": "Welcome!",
        "body": "<html>...</html>",
        "receivedAt": "2026-01-31T10:00:00"
    }
}
```

---

## Test Scenarios

### ✅ Happy Path

| # | Test | Expected |
|---|------|----------|
| 1 | Register new user | 200 OK |
| 2 | Login with credentials | 200 OK, tokens returned |
| 3 | Access protected endpoint | 200 OK, data returned |
| 4 | Refresh token | 200 OK, new tokens |
| 5 | Logout | 200 OK, session cleared |

### ❌ Error Cases

| # | Test | Expected |
|---|------|----------|
| 1 | Register existing email | 409 Conflict, `BUSINESS_001` |
| 2 | Login wrong password | 401 Unauthorized, `AUTH_001` |
| 3 | Access without token | 401 Unauthorized, `AUTH_006` |
| 4 | Access with expired token | 401 Unauthorized, `AUTH_002` |
| 5 | Refresh with invalid token | 401 Unauthorized, `AUTH_004` |

---

## Troubleshooting

### "401 Unauthorized" on protected endpoints
- ✅ Kiểm tra đã login và có access_token
- ✅ Kiểm tra header: `Authorization: Bearer <token>`
- ✅ Token có thể đã expired → dùng refresh token

### Google login fails
- ✅ Kiểm tra Google Client ID trong `application.yaml`
- ✅ ID Token phải fresh (expires sau vài phút)
- ✅ Token phải từ đúng Google project

### Refresh token fails
- ✅ Refresh token chỉ dùng 1 lần (rotated after use)
- ✅ Kiểm tra refresh token chưa expired (7 days by default)
