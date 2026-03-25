package com.awad.emailclientai.modules.email.controller;

import com.awad.emailclientai.modules.email.dto.response.StatisticsResponseDto;
import com.awad.emailclientai.modules.email.service.EmailStatsService;
import com.awad.emailclientai.shared.dto.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/statistics")
@RequiredArgsConstructor
public class EmailStatsController {

    private final EmailStatsService emailStatsService;

    @GetMapping
    public ResponseEntity<ApiResponse<StatisticsResponseDto>> getStats(
            @RequestParam Long accountId,
            @RequestParam(defaultValue = "30d") String period) {
        StatisticsResponseDto stats = emailStatsService.getStatistics(accountId, period);
        return ResponseEntity.ok(ApiResponse.success(stats));
    }
}
