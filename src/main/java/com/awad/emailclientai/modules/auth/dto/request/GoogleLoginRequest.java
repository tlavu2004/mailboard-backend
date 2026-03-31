package com.awad.emailclientai.modules.auth.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GoogleLoginRequest {

    private String idToken;
    
    @NotBlank(message = "Authorization code is required")
    private String code;

    private String accessToken;

    private String refreshToken;
}