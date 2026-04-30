package com.awad.emailclientai.modules.email.service;

import com.awad.emailclientai.modules.email.repository.EmailRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
@Slf4j
public class TrashCleanupService {

    private final EmailRepository emailRepository;

    /**
     * Runs every hour to clean up emails in TRASH for more than 30 days.
     */
    @Scheduled(fixedRate = 3600000) // 1 hour
    public void cleanupOldTrash() {
        LocalDateTime threshold = LocalDateTime.now().minusDays(30);
        log.info("[TRASH-CLEANUP] Starting cleanup of emails in TRASH deleted before {}", threshold);
        try {
            emailRepository.deleteOldTrash(threshold);
            log.info("[TRASH-CLEANUP] Cleanup completed successfully.");
        } catch (Exception e) {
            log.error("[TRASH-CLEANUP] Error during trash cleanup: {}", e.getMessage());
        }
    }
}
