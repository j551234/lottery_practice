package com.practice.lottery.controller;

import com.practice.lottery.config.security.JwtUtil;
import com.practice.lottery.controller.request.LoginRequest;
import com.practice.lottery.controller.response.LoginResponse;
import com.practice.lottery.dao.entity.User;
import com.practice.lottery.dao.repository.UserRepository;
import com.practice.lottery.dto.ApiResponse;
import com.practice.lottery.exception.AuthenticationException;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/auth")
public class AuthController {

    private final UserRepository userRepository;
    private final JwtUtil jwtUtil;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(10);

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<LoginResponse>> login(
            @RequestBody  @Valid LoginRequest req
    ) {
        // Find user
        User user = userRepository.findByUsername(req.getUsername())
                .orElseThrow(() -> new AuthenticationException("User not found"));

        // Verify password
        if (!encoder.matches(req.getPassword(), user.getPassword())) {
            throw new AuthenticationException("Invalid username or password");
        }

        // Generate token
        String token = jwtUtil.generateToken(user.getId(), user.getUsername(), user.getRole());

        // Build response
        LoginResponse loginResponse = LoginResponse.builder()
                .token(token)
                .tokenType("Bearer")
                .userId(user.getId())
                .username(user.getUsername())
                .role(user.getRole())
                .build();

        return ResponseEntity.ok(
                ApiResponse.success("Login successful", loginResponse)
        );
    }
}