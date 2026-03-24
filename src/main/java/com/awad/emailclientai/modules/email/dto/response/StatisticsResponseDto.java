package com.awad.emailclientai.modules.email.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class StatisticsResponseDto {
    private List<EmailStatusStatsDto> statusStats;
    private List<EmailTrendPointDto> emailTrend;
    private List<TopSenderDto> topSenders;
    private List<DailyActivityDto> dailyActivity;
    private long totalEmails;
    private long unreadCount;
    private long starredCount;
    private String period; // "7d", "30d", "90d"
}
