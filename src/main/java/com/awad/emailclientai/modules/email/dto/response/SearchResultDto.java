package com.awad.emailclientai.modules.email.dto.response;

import lombok.*;

/**
 * DTO for search results with relevance score.
 * Wraps the email data with a similarity score for ranked results.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SearchResultDto {
    private EmailEntityDto email;
    private double relevanceScore;
}
