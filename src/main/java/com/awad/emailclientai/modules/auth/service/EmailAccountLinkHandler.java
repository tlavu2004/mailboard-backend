package com.awad.emailclientai.modules.auth.service;

import com.awad.emailclientai.modules.email.dto.request.ConnectEmailAccountRequestDto;
import com.awad.emailclientai.modules.email.service.EmailAccountService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class EmailAccountLinkHandler {
    private final EmailAccountService emailAccountService;

    public void linkAccount(Long userId, ConnectEmailAccountRequestDto request) {
        try {
            emailAccountService.connectAccount(userId, request);
            log.info("Successfully auto-linked email account: {} for user: {}", request.getEmailAddress(), userId);
        } catch (Exception e) {
            log.error("Failed to auto-link email account: {} for user: {}. Error: {}", 
                request.getEmailAddress(), userId, e.getMessage());
        }
    }
}
