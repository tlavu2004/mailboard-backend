# Hướng dẫn Giao tiếp Frontend & Backend - LinkForge

Tài liệu này hướng dẫn cách Frontend tích hợp và tương tác với các API của LinkForge Backend.

---

## 1. Tổng quan Kiến trúc (High-level Overview)

LinkForge sử dụng mô hình **RESTful API** để giao tiếp. Frontend (thường là React/Next.js) sẽ gửi các HTTP Request tới Backend (Spring Boot) và nhận về dữ liệu định dạng JSON.

- **Giao thức**: HTTP/HTTPS
- **Định dạng dữ liệu**: JSON
- **Cổng mặc định (Local/Docker)**: `8080` (được ánh xạ từ container trong `docker-compose.yml`)
- **API Prefix**: `/api/v1/`

---

## 2. Base URL & Versioning

Tất cả các API endpoint (ngoại trừ link rút gọn) đều bắt đầu với tiền tố `/api/v1/`.

### 2.1 Truy cập từ Máy chủ Local (Host Machine)
Nếu Frontend của bạn chạy trực tiếp trên máy tính (không qua Docker) và Backend chạy qua Docker:
- **Base URL**: `http://localhost:8080/api/v1`

### 2.2 Truy cập trong mạng Docker (Container-to-Container)
Nếu cả Frontend và Backend đều chạy trong Docker Compose:
- **Base URL**: `http://app:8080/api/v1` (Sử dụng service name `app` làm hostname)

---

## 3. Cấu trúc Response Chuẩn (Standard Response Format)

Mọi phản hồi từ server đều được bọc trong một đối tượng `ApiResponse` thống nhất:

```json
{
  "success": true,       // Trạng thái thành công của request
  "message": "Success",  // Thông báo từ server (có hỗ trợ i18n)
  "data": { ... },       // Dữ liệu trả về (Object, Array hoặc null)
  "timestamp": "..."     // Thời điểm phản hồi (ISO Instant)
}
```

---

## 4. Xác thực (Authentication)

LinkForge sử dụng **JWT (JSON Web Token)** để bảo mật các endpoint yêu cầu quyền riêng tư.

### Luồng xác thực:
1. Frontend gửi credentials tới `/auth/login`.
2. Backend trả về `accessToken` và `refreshToken`.
3. Frontend lưu trữ Token (thường trong LocalStorage hoặc HttpOnly Cookie - tùy cấu hình).
4. Các request sau đó phải đính kèm Token vào Header:
   ```http
   Authorization: Bearer <your_access_token>
   ```

### Refresh Token:
Khi `accessToken` hết hạn (mặc định 15 phút), Frontend cần gọi `/auth/refresh` với `refreshToken` để lấy Token mới mà không bắt người dùng đăng nhập lại.

---

## 5. Xử lý lỗi (Error Handling)

Khi có lỗi xảy ra (4xx, 5xx), client vẫn nhận được cấu trúc JSON chuẩn với `success: false`.

| Mã lỗi (HTTP Status) | Ý nghĩa |
|----------------------|---------|
| **400 Bad Request**  | Dữ liệu gửi lên không hợp lệ (Validation fail) |
| **401 Unauthorized** | Chưa đăng nhập hoặc Token hết hạn |
| **403 Forbidden**    | Không có quyền truy cập (Sai Role) |
| **404 Not Found**   | Không tìm thấy resource |
| **410 Gone**        | Link đã hết hạn |
| **429 Too Many Requests** | Vượt quá giới hạn Rate Limit (mặc định 60 requests/phút) |
| **500 Internal Error** | Lỗi server hệ thống |

---

## 6. Cấu hình CORS (Cross-Origin Resource Sharing)

Để Frontend có thể gọi API từ một domain khác, domain đó phải được khai báo trong Backend.

- **Cấu hình tại**: `CorsConfig.java`
- **Biến môi trường**: `FRONTEND_URL` (mặc định: `http://localhost:5173`)
- **Header cho phép**: `Authorization`, `Content-Type`, `X-Requested-With`, v.v.
- **Method cho phép**: `GET`, `POST`, `PUT`, `DELETE`, `OPTIONS`, `PATCH`.

---

## 7. Các Endpoint đặc biệt

### Redirect Service
Link rút gọn không nằm trong prefix `/api/v1/` để giữ URL ngắn nhất có thể.
- **Endpoint**: `/r/{shortCode}`
- **Behavior**: Backend trả về `301 Moved Permanently` hoặc `302 Found` tới URL gốc.

### Payment Integration (VNPay)
- Sau khi thanh toán, VNPay sẽ redirect về endpoint callback của Backend.
- Frontend cần lắng nghe trạng thái từ Backend để cập nhật UI (thông qua polling hoặc websocket/event - tùy implementation).

---

## 8. Snippet Code mẫu (Axios - React)

```javascript
import axios from 'axios';

const api = axios.create({
  baseURL: 'http://localhost:8080/api/v1',
  withCredentials: true // Quan trọng để gửi kèm cookie/session nếu có
});

// Thêm interceptor để tự động chèn Token
api.interceptors.request.use(config => {
  const token = localStorage.getItem('token');
  if (token) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});

// Gọi API tạo link
const createShortLink = async (originalUrl) => {
  try {
    const response = await api.post('/links', { originalUrl });
    return response.data; // Đây là ApiResponse
  } catch (error) {
    console.error("Lỗi:", error.response.data.message);
    throw error;
  }
};
```

---
*Tài liệu này được cập nhật tự động bởi Antigravity AI.*
