package com.codecard.auth;

import com.codecard.auth.dto.*;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest req) {
        return ResponseEntity.ok(authService.register(req));
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest req) {
        return ResponseEntity.ok(authService.login(req));
    }

    @PostMapping("/send-otp")
    public ResponseEntity<Map<String, Object>> sendOtp(@Valid @RequestBody OtpSendRequest req) {
        authService.sendOtp(req);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @PostMapping("/verify-otp")
    public ResponseEntity<AuthResponse> verifyOtp(@Valid @RequestBody OtpVerifyRequest req) {
        return ResponseEntity.ok(authService.verifyOtp(req));
    }

    @PostMapping("/set-password")
    public ResponseEntity<Map<String, Object>> setPassword(
            @AuthenticationPrincipal String userId,
            @Valid @RequestBody SetPasswordRequest req) {
        authService.setPassword(UUID.fromString(userId), req.getPassword());
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refresh(@Valid @RequestBody RefreshRequest req) {
        return ResponseEntity.ok(authService.refresh(req.getRefreshToken()));
    }

    @GetMapping("/me")
    public ResponseEntity<AuthResponse.UserProfile> me(@AuthenticationPrincipal String userId) {
        return ResponseEntity.ok(authService.getProfile(UUID.fromString(userId)));
    }

    @PutMapping("/profile")
    public ResponseEntity<AuthResponse.UserProfile> updateProfile(
            @AuthenticationPrincipal String userId,
            @Valid @RequestBody UpdateProfileRequest req) {
        return ResponseEntity.ok(authService.updateProfile(UUID.fromString(userId), req));
    }

    @PostMapping("/logout")
    public ResponseEntity<Map<String, Object>> logout(
            @AuthenticationPrincipal String userId,
            @RequestBody(required = false) Map<String, String> body) {
        String refreshToken = body != null ? body.get("refreshToken") : null;
        authService.logout(refreshToken);
        return ResponseEntity.ok(Map.of("ok", true));
    }
}
