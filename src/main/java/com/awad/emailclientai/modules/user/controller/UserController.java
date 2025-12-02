package com.awad.emailclientai.modules.user.controller;

import com.awad.emailclientai.modules.user.dto.request.UpdateProfileRequest;
import com.awad.emailclientai.modules.user.dto.response.UserResponse;
import com.awad.emailclientai.modules.user.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @GetMapping("/me")
    public ResponseEntity<UserResponse> getCurrentUser(Authentication authentication) {
        String email = authentication.getName();
        UserResponse user = userService.getCurrentUser(email);
        return ResponseEntity.ok(user);
    }

    @PutMapping("/me")
    public ResponseEntity<UserResponse> updateProfile(
            Authentication authentication,
            @Valid @RequestBody UpdateProfileRequest request
    ) {
        String email = authentication.getName();
        UserResponse updatedUser = userService.updateProfile(email, request);
        return ResponseEntity.ok(updatedUser);
    }

    @GetMapping("/{id}")
    public ResponseEntity<UserResponse> getUserById(@PathVariable Long id) {
        UserResponse user = userService.getUserById(id);
        return ResponseEntity.ok(user);
    }
}