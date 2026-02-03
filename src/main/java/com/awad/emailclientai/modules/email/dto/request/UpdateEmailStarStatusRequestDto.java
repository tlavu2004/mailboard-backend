package com.awad.emailclientai.modules.email.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class UpdateEmailStarStatusRequestDto {
    @NotNull
    private Boolean starred;
}
