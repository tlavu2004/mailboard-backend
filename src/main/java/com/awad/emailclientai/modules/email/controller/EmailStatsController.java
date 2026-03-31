package com.awad.emailclientai.modules.email.controller;

import com.awad.emailclientai.modules.email.dto.response.StatisticsResponseDto;
import com.awad.emailclientai.modules.email.repository.EmailAccountRepository;
import com.awad.emailclientai.modules.email.service.EmailStatsService;
import com.awad.emailclientai.modules.user.security.UserPrincipal;
import com.awad.emailclientai.shared.dto.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/statistics")
@RequiredArgsConstructor
public class EmailStatsController {

    private final EmailStatsService emailStatsService;
    private final EmailAccountRepository emailAccountRepository;

    @GetMapping
    public ResponseEntity<ApiResponse<StatisticsResponseDto>> getStats(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(required = false) Long accountId,
            @RequestParam(defaultValue = "30d") String period) {
        
        if (accountId == null) {
            accountId = emailAccountRepository.findByUserIdAndActiveTrue(principal.getId())
                    .stream().findFirst()
                    .orElseThrow(() -> new RuntimeException("No active email account found"))
                    .getId();
        }
        
        StatisticsResponseDto stats = emailStatsService.getStatistics(accountId, period);
        return ResponseEntity.ok(ApiResponse.success(stats));
    }
}
