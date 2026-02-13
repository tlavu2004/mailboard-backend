package com.awad.emailclientai.modules.email.controller;

import com.awad.emailclientai.modules.email.entity.EmailEntity;
import com.awad.emailclientai.modules.email.service.SearchService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/search")
@RequiredArgsConstructor
public class SearchController {

    private final SearchService searchService;

    @PostMapping("/semantic")
    @Operation(summary = "AI Semantic Email Search", description = "Search emails using AI embeddings for conceptual matching.")
    public ResponseEntity<List<EmailEntity>> semanticSearch(@RequestBody SearchRequest request) {
        return ResponseEntity.ok(searchService.semanticSearch(request.getQuery()));
    }

    @GetMapping("/suggestions")
    @Operation(summary = "Search Suggestions Provider", description = "Provides real-time subject and sender suggestions as the user types.")
    public ResponseEntity<List<String>> getSuggestions(@RequestParam("q") String query) {
        return ResponseEntity.ok(searchService.getSuggestions(query));
    }


    @Data
    public static class SearchRequest {
        private String query;
    }
}
