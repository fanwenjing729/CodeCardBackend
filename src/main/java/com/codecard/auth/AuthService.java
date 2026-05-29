package com.codecard.auth;

import com.codecard.auth.dto.*;
import com.codecard.config.JwtService;
import com.codecard.user.User;
import com.codecard.user.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class AuthService {

    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[\\w.-]+@[\\w.-]+\\.\\w{2,}$");

    private final UserRepository userRepo;
    private final RefreshTokenRepository refreshTokenRepo;
    private final OtpService otpService;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthService(UserRepository userRepo, RefreshTokenRepository refreshTokenRepo,
                       OtpService otpService, PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.userRepo = userRepo;
        this.refreshTokenRepo = refreshTokenRepo;
        this.otpService = otpService;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    @Transactional
    public AuthResponse register(RegisterRequest req) {
        if (req.getEmail() != null && userRepo.existsByEmail(req.getEmail())) {
            throw new AuthException("email already registered");
        }
        if (req.getPhone() != null && userRepo.existsByPhone(req.getPhone())) {
            throw new AuthException("phone already registered");
        }

        User user = new User();
        user.setEmail(req.getEmail());
        user.setPhone(req.getPhone());
        user.setPasswordHash(passwordEncoder.encode(req.getPassword()));
        userRepo.save(user);

        return buildAuthResponse(user, true);
    }

    @Transactional
    public AuthResponse login(LoginRequest req) {
        User user;
        if (req.getEmail() != null) {
            user = userRepo.findByEmail(req.getEmail())
                    .orElseThrow(() -> new AuthException("invalid credentials"));
        } else {
            user = userRepo.findByPhone(req.getPhone())
                    .orElseThrow(() -> new AuthException("invalid credentials"));
        }

        // 检查账户是否被锁定
        if (user.getLockedUntil() != null && user.getLockedUntil().isAfter(Instant.now())) {
            throw new AuthException("account temporarily locked, try again later");
        }

        if (user.getPasswordHash() == null || !passwordEncoder.matches(req.getPassword(), user.getPasswordHash())) {
            user.setLoginFailures(user.getLoginFailures() + 1);
            if (user.getLoginFailures() >= 5) {
                user.setLockedUntil(Instant.now().plus(15, ChronoUnit.MINUTES));
            }
            userRepo.save(user);
            throw new AuthException("invalid credentials");
        }

        // 登录成功，重置失败计数
        user.setLoginFailures(0);
        user.setLockedUntil(null);
        userRepo.save(user);

        return buildAuthResponse(user, false);
    }

    public void sendOtp(OtpSendRequest req) {
        otpService.sendCode(req.getTarget(), req.getPurpose());
    }

    @Transactional
    public AuthResponse verifyOtp(OtpVerifyRequest req) {
        if (!otpService.verifyCode(req.getTarget(), req.getCode(), req.getPurpose())) {
            throw new AuthException("invalid or expired code");
        }

        // Find or create user
        User user;
        boolean isEmail = isEmail(req.getTarget());
        if (!isEmail && !req.getTarget().matches("^\\+?\\d{7,15}$")) {
            throw new AuthException("invalid phone number format");
        }
        if (isEmail) {
            user = userRepo.findByEmail(req.getTarget()).orElse(null);
        } else {
            user = userRepo.findByPhone(req.getTarget()).orElse(null);
        }

        boolean isNewUser = false;
        if (user == null) {
            user = new User();
            if (isEmail) {
                user.setEmail(req.getTarget());
            } else {
                user.setPhone(req.getTarget());
            }
            userRepo.save(user);
            isNewUser = true;
        }

        return buildAuthResponse(user, isNewUser);
    }

    @Transactional
    public void setPassword(UUID userId, String password) {
        User user = userRepo.findById(userId)
                .orElseThrow(() -> new AuthException("user not found"));
        user.setPasswordHash(passwordEncoder.encode(password));
        userRepo.save(user);
    }

    @Transactional
    public AuthResponse refresh(String refreshTokenStr) {
        if (!jwtService.isTokenValid(refreshTokenStr)) {
            throw new AuthException("invalid refresh token");
        }

        if (!"refresh".equals(jwtService.extractType(refreshTokenStr))) {
            throw new AuthException("invalid refresh token");
        }

        String jid = jwtService.extractJid(refreshTokenStr);
        RefreshToken saved = refreshTokenRepo.findByTokenJid(jid)
                .orElseThrow(() -> new AuthException("token revoked"));

        if (saved.getExpiresAt().isBefore(Instant.now())) {
            refreshTokenRepo.delete(saved);
            throw new AuthException("token expired");
        }

        // Rotation: delete old, issue new
        refreshTokenRepo.delete(saved);

        UUID userId = jwtService.extractUserId(refreshTokenStr);
        User user = userRepo.findById(userId)
                .orElseThrow(() -> new AuthException("user not found"));

        return buildAuthResponse(user, false);
    }

    public AuthResponse.UserProfile getProfile(UUID userId) {
        User user = userRepo.findById(userId)
                .orElseThrow(() -> new AuthException("user not found"));
        return toProfile(user);
    }

    @Transactional
    public AuthResponse.UserProfile updateProfile(UUID userId, UpdateProfileRequest req) {
        User user = userRepo.findById(userId)
                .orElseThrow(() -> new AuthException("user not found"));
        if (req.getDisplayId() != null) {
            user.setDisplayId(req.getDisplayId());
        }
        if (req.getAvatarUrl() != null) {
            user.setAvatarUrl(req.getAvatarUrl());
        }
        userRepo.save(user);
        return toProfile(user);
    }

    @Transactional
    public void logout(UUID userId) {
        refreshTokenRepo.deleteByUserId(userId);
    }

    private AuthResponse buildAuthResponse(User user, boolean isNewUser) {
        // Clean up expired tokens for this user
        refreshTokenRepo.deleteExpiredByUserId(user.getId(), Instant.now());

        String accessToken = jwtService.generateAccessToken(user.getId());
        String refreshToken = jwtService.generateRefreshToken(user.getId());

        RefreshToken rt = new RefreshToken();
        rt.setUserId(user.getId());
        rt.setTokenJid(jwtService.extractJid(refreshToken));
        rt.setExpiresAt(Instant.now().plusMillis(jwtService.getRefreshExpirationMs()));
        refreshTokenRepo.save(rt);

        AuthResponse resp = new AuthResponse();
        resp.setUser(toProfile(user));
        resp.setAccessToken(accessToken);
        resp.setRefreshToken(refreshToken);
        resp.setIsNewUser(isNewUser);
        return resp;
    }

    private boolean isEmail(String target) {
        return EMAIL_PATTERN.matcher(target).matches();
    }

    private AuthResponse.UserProfile toProfile(User user) {
        AuthResponse.UserProfile profile = new AuthResponse.UserProfile();
        profile.setId(user.getId().toString());
        profile.setEmail(user.getEmail());
        profile.setPhone(user.getPhone());
        profile.setDisplayId(user.getDisplayId());
        profile.setAvatarUrl(user.getAvatarUrl());
        return profile;
    }
}
