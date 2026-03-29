package com.awad.emailclientai.modules.email.controller;

import com.awad.emailclientai.modules.email.dto.response.SemanticSearchResponse;
import com.awad.emailclientai.modules.email.dto.response.SuggestionDto;
import com.awad.emailclientai.modules.email.entity.EmailAccount;
import com.awad.emailclientai.modules.email.repository.EmailAccountRepository;
import com.awad.emailclientai.modules.email.service.SearchService;
import com.awad.emailclientai.modules.user.security.UserPrincipal;
import com.awad.emailclientai.shared.dto.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/search")
@RequiredArgsConstructor
@Slf4j
public class SearchController {

    private final SearchService searchService;
    private final EmailAccountRepository emailAccountRepository;

    @PostMapping("/semantic")
    @Operation(summary = "AI Semantic Email Search", description = "Search emails using AI embeddings for conceptual matching.")
    public ResponseEntity<ApiResponse<SemanticSearchResponse>> semanticSearch(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestBody SearchRequest request
    ) {
        log.info("API Search: Received /semantic request from user {}, query: '{}'", principal.getId(), request.getQuery());
        EmailAccount account = getPrimaryAccount(principal);
        return ResponseEntity.ok(ApiResponse.success(searchService.semanticSearch(account.getId(), request.getQuery())));
    }

    @GetMapping("/suggestions")
    @Operation(summary = "Search Suggestions Provider", description = "Provides real-time subject and sender suggestions as the user types.")
    public ResponseEntity<ApiResponse<List<SuggestionDto>>> getSuggestions(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam("q") String query
    ) {
        EmailAccount account = getPrimaryAccount(principal);
        return ResponseEntity.ok(ApiResponse.success(searchService.getSuggestions(account.getId(), query)));
    }

    private EmailAccount getPrimaryAccount(UserPrincipal principal) {
        return emailAccountRepository.findByUserIdAndActiveTrue(principal.getId())
                .stream().findFirst()
                .orElseThrow(() -> new RuntimeException("No active email account linked."));
    }


    @Data
    public static class SearchRequest {
        private String query;
        private Integer limit;
    }
}
