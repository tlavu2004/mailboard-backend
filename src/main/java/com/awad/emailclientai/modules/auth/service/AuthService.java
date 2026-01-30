package com.awad.emailclientai.modules.auth.service;

import com.awad.emailclientai.modules.auth.dto.request.GoogleLoginRequest;
import com.awad.emailclientai.modules.auth.dto.request.LoginRequest;
import com.awad.emailclientai.modules.auth.dto.request.RefreshTokenRequest;
import com.awad.emailclientai.modules.auth.dto.request.RegisterRequest;
import com.awad.emailclientai.modules.auth.dto.response.AuthResponse;
import com.awad.emailclientai.modules.auth.entity.RefreshToken;
import com.awad.emailclientai.modules.user.entity.User;
import com.awad.emailclientai.modules.user.repository.UserRepository;
import com.awad.emailclientai.shared.config.properties.JwtProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final RefreshTokenService refreshTokenService;
    private final GoogleAuthService googleAuthService;
    private final AuthenticationManager authenticationManager;
    private final UserDetailsService userDetailsService;
    private final JwtProperties jwtProperties;

    @Transactional
    public void register(RegisterRequest request) {
        log.debug("Registering new user: {}", request.getEmail());

        if (userRepository.existsByEmail(request.getEmail())) {
            throw new RuntimeException("User already exists with email: " + request.getEmail());
        }

        User user = User.builder()
                .email(request.getEmail())
                .password(passwordEncoder.encode(request.getPassword()))
                .name(request.getName())
                .build();

        userRepository.save(user);
        log.info("User registered successfully: {}", user.getEmail());
    }

    @Transactional
    public AuthResponse login(LoginRequest request) {
        log.debug("User login attempt: {}", request.getEmail());

        authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.getEmail(), request.getPassword())
        );

        User user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new RuntimeException("User not found"));

        log.info("User logged in successfully: {}", user.getEmail());
        return generateAuthResponse(user);
    }

    @Transactional
    public AuthResponse googleLogin(GoogleLoginRequest request) {
        log.debug("Google login attempt");

        User user = googleAuthService.authenticateGoogleUser(request.getIdToken());
        log.info("Google user logged in successfully: {}", user.getEmail());

        return generateAuthResponse(user);
    }

    @Transactional
    public AuthResponse refreshToken(RefreshTokenRequest request) {
        log.debug("Refresh token request");

        RefreshToken refreshToken = refreshTokenService.findByToken(request.getRefreshToken());
        refreshTokenService.verifyExpiration(refreshToken);

        User user = refreshToken.getUser();
        UserDetails userDetails = userDetailsService.loadUserByUsername(user.getEmail());

        String newAccessToken = jwtService.generateAccessToken(userDetails);

        log.info("Access token refreshed for user: {}", user.getEmail());

        return AuthResponse.builder()
                .accessToken(newAccessToken)
                .refreshToken(refreshToken.getToken())
                .tokenType("Bearer")
                .expiresIn(jwtProperties.getAccessTokenExpiration() / 1000)
                .build();
    }

    @Transactional
    public void logout(String refreshToken) {
        log.debug("Logout request");
        // Verify token exists before deleting to avoid idempotent 200 OK on duplicate logout
        refreshTokenService.findByToken(refreshToken);
        
        refreshTokenService.deleteByToken(refreshToken);
        log.info("User logged out successfully");
    }

    private AuthResponse generateAuthResponse(User user) {
        UserDetails userDetails = userDetailsService.loadUserByUsername(user.getEmail());

        String accessToken = jwtService.generateAccessToken(userDetails);
        String refreshToken = jwtService.generateRefreshToken(userDetails);
        refreshTokenService.createRefreshToken(user, refreshToken);

        return AuthResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .tokenType("Bearer")
                .expiresIn(jwtProperties.getAccessTokenExpiration() / 1000)
                .build();
    }
}