package com.awad.emailclientai.modules.email.controller;

import com.awad.emailclientai.modules.email.dto.response.StatisticsResponseDto;
import com.awad.emailclientai.modules.email.service.EmailStatsService;
import com.awad.emailclientai.shared.dto.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/statistics")
@RequiredArgsConstructor
@Tag(name = "Statistics", description = "Email statistics and analytics for dashboard")
public class EmailStatsController {

    private final EmailStatsService emailStatsService;

    @GetMapping
    @Operation(summary = "Get Dashboard Statistics", description = "Returns status distribution, trends, and top senders.")
    public ResponseEntity<ApiResponse<StatisticsResponseDto>> getStatistics(
            @RequestParam Long accountId,
            @RequestParam(defaultValue = "30d") String period) {
        
        StatisticsResponseDto stats = emailStatsService.getStatistics(accountId, period);
        return ResponseEntity.ok(ApiResponse.success(stats));
    }
}
