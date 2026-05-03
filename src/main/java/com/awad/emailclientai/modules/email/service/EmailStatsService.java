package com.awad.emailclientai.modules.email.service;

import com.awad.emailclientai.modules.email.dto.response.*;
import com.awad.emailclientai.modules.email.repository.EmailRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class EmailStatsService {

    private final EmailRepository emailRepository;

    public StatisticsResponseDto getStatistics(Long accountId, String period) {
        LocalDateTime since = calculateSinceDate(period);

        return StatisticsResponseDto.builder()
                .statusStats(emailRepository.countByStatusForAccount(accountId, since))
                .emailTrend(emailRepository.getEmailTrend(accountId, since).stream()
                        .map(row -> new EmailTrendPointDto((String) row[0], ((Number) row[1]).longValue()))
                        .toList())
                .topSenders(emailRepository.getTopSenders(accountId, since).stream()
                        .map(row -> new TopSenderDto(null, (String) row[0], ((Number) row[1]).longValue()))
                        .toList())
                .dailyActivity(emailRepository.getDailyActivity(accountId, since).stream()
                        .map(row -> new DailyActivityDto(((Number) row[0]).intValue(), ((Number) row[1]).intValue(), ((Number) row[2]).longValue()))
                        .toList())
                .totalEmails(emailRepository.countByAccountId(accountId, since))
                .unreadCount(emailRepository.countUnreadByAccountId(accountId))
                .starredCount(emailRepository.countStarredByAccountId(accountId))
                .period(period)
                .build();
    }

    private LocalDateTime calculateSinceDate(String period) {
        return switch (period.toLowerCase()) {
            case "7d" -> LocalDateTime.now().minusDays(7);
            case "30d" -> LocalDateTime.now().minusDays(30);
            case "90d" -> LocalDateTime.now().minusDays(90);
            default -> LocalDateTime.now().minusDays(30);
        };
    }
}
