package com.awad.emailclientai.modules.user.service;

import com.awad.emailclientai.modules.user.entity.User;
import com.awad.emailclientai.modules.user.dto.request.UpdateProfileRequest;
import com.awad.emailclientai.modules.user.dto.response.UserResponse;
import com.awad.emailclientai.modules.user.mapper.UserMapper;
import com.awad.emailclientai.modules.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class UserService {

    private final UserRepository userRepository;
    private final UserMapper userMapper;

    @Transactional(readOnly = true)
    public UserResponse getCurrentUser(String email) {
        log.debug("Getting user by email: {}", email);
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found with email: " + email));
        return userMapper.toResponse(user);
    }

    @Transactional(readOnly = true)
    public UserResponse getUserById(Long id) {
        log.debug("Getting user by id: {}", id);
        User user = userRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("User not found with id: " + id));
        return userMapper.toResponse(user);
    }

    @Transactional
    public UserResponse updateProfile(String email, UpdateProfileRequest request) {
        log.debug("Updating profile for user: {}", email);

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found with email: " + email));

        user.setName(request.getName());
        User updatedUser = userRepository.save(user);

        log.info("Profile updated for user: {}", email);
        return userMapper.toResponse(updatedUser);
    }
}