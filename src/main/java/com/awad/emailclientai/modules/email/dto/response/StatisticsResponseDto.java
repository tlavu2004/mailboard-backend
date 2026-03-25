package com.awad.emailclientai.modules.email.dto.response;

import lombok.Builder;
import lombok.Data;
import java.util.List;

@Data
@Builder
public class StatisticsResponseDto {
    private List<EmailStatusStatsDto> statusStats;
    private List<EmailTrendPointDto> emailTrend;
    private List<TopSenderDto> topSenders;
    private List<DailyActivityDto> dailyActivity;
    private long totalEmails;
    private long unreadCount;
    private long starredCount;
    private String period;
}
