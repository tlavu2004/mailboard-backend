package com.awad.emailclientai.modules.auth.dto.response;

import com.awad.emailclientai.modules.user.entity.AuthProvider;
import com.awad.emailclientai.modules.user.entity.User;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserResponse {

    private Long id;
    private String email;
    private String name;
    private String picture;
    private String provider;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static UserResponse from(User user) {
        String provider = AuthProvider.GOOGLE.equals(user.getAuthProvider()) ? "google" : "email";
        return UserResponse.builder()
                .id(user.getId())
                .email(user.getEmail())
                .name(user.getName())
                .picture(null) // Google picture URL not stored currently
                .provider(provider)
                .createdAt(user.getCreatedAt())
                .updatedAt(user.getUpdatedAt())
                .build();
    }
}
