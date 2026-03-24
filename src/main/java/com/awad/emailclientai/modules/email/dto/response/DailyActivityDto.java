package com.awad.emailclientai.modules.email.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class DailyActivityDto {
    private int dayOfWeek; // 0=Sunday, 6=Saturday
    private int hour;      // 0-23
    private long count;
}
