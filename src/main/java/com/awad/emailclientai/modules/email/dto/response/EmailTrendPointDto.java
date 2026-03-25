package com.awad.emailclientai.modules.email.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class EmailTrendPointDto {
    private String date;
    private long count;
}
