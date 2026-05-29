package com.codecard.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.regex.Pattern;

@Service
public class OtpService {

    private static final Logger log = LoggerFactory.getLogger(OtpService.class);
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[\\w.-]+@[\\w.-]+\\.\\w{2,}$");

    private final OtpCodeRepository otpRepo;
    private final JavaMailSender mailSender;
    private final SecureRandom random = new SecureRandom();

    public OtpService(OtpCodeRepository otpRepo, JavaMailSender mailSender) {
        this.otpRepo = otpRepo;
        this.mailSender = mailSender;
    }

    @Transactional
    public void sendCode(String target, String purpose) {
        // Rate limit: same target+purpose cannot send another OTP within 60 seconds
        otpRepo.findTopByTargetAndPurposeAndUsedFalseOrderByCreatedAtDesc(target, purpose)
                .ifPresent(recent -> {
                    if (recent.getCreatedAt().plusSeconds(60).isAfter(Instant.now())) {
                        throw new AuthException("please wait before requesting another code");
                    }
                });

        String code = String.format("%06d", random.nextInt(1_000_000));

        OtpCode otp = new OtpCode();
        otp.setTarget(target);
        otp.setCode(code);
        otp.setPurpose(purpose);
        otp.setExpiresAt(Instant.now().plus(10, ChronoUnit.MINUTES));
        otp.setUsed(false);
        otpRepo.save(otp);

        if (EMAIL_PATTERN.matcher(target).matches()) {
            sendEmail(target, code, purpose);
        } else {
            log.info("OTP for {}: {} (SMS not yet implemented)", target, code);
        }
    }

    @Transactional
    public boolean verifyCode(String target, String code, String purpose) {
        return otpRepo.findTopByTargetAndPurposeAndUsedFalseOrderByCreatedAtDesc(target, purpose)
                .map(otp -> {
                    if (otp.getExpiresAt().isBefore(Instant.now())) {
                        return false;
                    }
                    if (!otp.getCode().equals(code)) {
                        return false;
                    }
                    otp.setUsed(true);
                    otpRepo.save(otp);
                    return true;
                })
                .orElse(false);
    }

    private void sendEmail(String to, String code, String purpose) {
        try {
            SimpleMailMessage msg = new SimpleMailMessage();
            msg.setTo(to);
            msg.setSubject("CodeCard — " + purposeLabel(purpose));
            msg.setText("Your verification code is: " + code + "\n\nThis code expires in 10 minutes.");
            mailSender.send(msg);
        } catch (Exception e) {
            log.warn("Failed to send OTP email to {}: {}", to, e.getMessage());
            log.info("OTP code for {} ({}): {}", to, purpose, code);
        }
    }

    private String purposeLabel(String purpose) {
        return switch (purpose) {
            case "register" -> "Registration Code";
            case "reset" -> "Password Reset Code";
            default -> "Login Code";
        };
    }
}
