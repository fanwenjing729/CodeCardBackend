package com.codecard.config;

import com.codecard.auth.OtpCodeRepository;
import com.codecard.auth.RefreshTokenRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Component
public class CleanupScheduler {

    private static final Logger log = LoggerFactory.getLogger(CleanupScheduler.class);

    private final OtpCodeRepository otpRepo;
    private final RefreshTokenRepository refreshTokenRepo;

    public CleanupScheduler(OtpCodeRepository otpRepo, RefreshTokenRepository refreshTokenRepo) {
        this.otpRepo = otpRepo;
        this.refreshTokenRepo = refreshTokenRepo;
    }

    @Scheduled(cron = "0 7 3 * * *")
    @Transactional
    public void cleanExpiredData() {
        int otps = otpRepo.deleteUsedOrExpired(Instant.now());
        int tokens = refreshTokenRepo.deleteAllExpired(Instant.now());
        if (otps > 0 || tokens > 0) {
            log.info("Cleaned {} OTP codes, {} expired refresh tokens", otps, tokens);
        }
    }
}
