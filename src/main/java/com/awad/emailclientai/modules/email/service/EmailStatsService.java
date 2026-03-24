package com.awad.emailclientai.modules.email.service;

import com.awad.emailclientai.modules.email.dto.response.*;
import com.awad.emailclientai.modules.email.repository.EmailRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class EmailStatsService {

    private final EmailRepository emailRepository;

    @Transactional(readOnly = true)
    public StatisticsResponseDto getStatistics(Long accountId, String period) {
        log.debug("Fetching statistics for account {} with period {}", accountId, period);

        int days = switch (period) {
            case "7d" -> 7;
            case "90d" -> 90;
            default -> 30;
        };

        LocalDateTime startDate = LocalDateTime.now().minusDays(days);

        // 1. Status Stats
        List<EmailStatusStatsDto> statusStats = emailRepository.getStatusStats(accountId);

        // 2. Email Trend
        List<Object[]> trendRows = emailRepository.getEmailTrend(accountId, startDate);
        List<EmailTrendPointDto> emailTrend = trendRows.stream()
                .map(row -> EmailTrendPointDto.builder()
                        .date((String) row[0])
                        .count(((Number) row[1]).longValue())
                        .build())
                .collect(Collectors.toList());

        // 3. Top Senders
        List<Object[]> senderRows = emailRepository.getTopSenders(accountId, 10);
        List<TopSenderDto> topSenders = senderRows.stream()
                .map(row -> {
                    String senderRaw = (String) row[0];
                    String name = senderRaw;
                    String email = "";

                    // Simple parsing for "Name <email@example.com>"
                    if (senderRaw != null && senderRaw.contains("<") && senderRaw.contains(">")) {
                        name = senderRaw.substring(0, senderRaw.indexOf("<")).trim();
                        email = senderRaw.substring(senderRaw.indexOf("<") + 1, senderRaw.indexOf(">")).trim();
                    } else if (senderRaw != null && senderRaw.contains("@")) {
                        email = senderRaw;
                        name = senderRaw.split("@")[0];
                    }

                    return TopSenderDto.builder()
                            .name(name)
                            .email(email)
                            .count(((Number) row[1]).longValue())
                            .build();
                })
                .collect(Collectors.toList());

        // 4. Daily Activity
        List<Object[]> activityRows = emailRepository.getDailyActivity(accountId, startDate);
        List<DailyActivityDto> dailyActivity = activityRows.stream()
                .map(row -> DailyActivityDto.builder()
                        .dayOfWeek(((Number) row[0]).intValue())
                        .hour(((Number) row[1]).intValue())
                        .count(((Number) row[2]).longValue())
                        .build())
                .collect(Collectors.toList());

        // 5. General Counts
        long total = emailRepository.countByAccountId(accountId);
        long unread = emailRepository.countUnreadByAccountId(accountId);
        long starred = emailRepository.countStarredByAccountId(accountId);

        return StatisticsResponseDto.builder()
                .statusStats(statusStats)
                .emailTrend(emailTrend)
                .topSenders(topSenders)
                .dailyActivity(dailyActivity)
                .totalEmails(total)
                .unreadCount(unread)
                .starredCount(starred)
                .period(period)
                .build();
    }
}
