package com.awad.emailclientai.modules.email.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SuggestionDto {
    private String text;
    private String type; // 'sender' | 'subject' | 'keyword'
    private double relevanceScore;
}
