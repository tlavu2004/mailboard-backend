package com.awad.emailclientai.modules.email.dto.response;

import lombok.Builder;
import lombok.Data;

/**
 * DTO for search results with relevance score.
 * Wraps the email data with a similarity score for ranked results.
 */
@Data
@Builder
public class SearchResultDto {
    private EmailEntityDto email;
    private double relevanceScore;
}
