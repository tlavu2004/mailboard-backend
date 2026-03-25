package com.awad.emailclientai.modules.auth.service;

import com.awad.emailclientai.modules.email.dto.request.ConnectEmailAccountRequestDto;
import com.awad.emailclientai.modules.email.service.EmailAccountService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class EmailAccountLinkHandler {
    private final EmailAccountService emailAccountService;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void linkAccount(Long userId, ConnectEmailAccountRequestDto request) {
        try {
            emailAccountService.connectAccount(userId, request);
        } catch (Exception e) {
            log.error("Failed to auto-link email account in isolated transaction for user {}: {}", userId, e.getMessage());
        }
    }
}
