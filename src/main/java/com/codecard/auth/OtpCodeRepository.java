package com.codecard.auth;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

public interface OtpCodeRepository extends JpaRepository<OtpCode, UUID> {
    Optional<OtpCode> findTopByTargetAndPurposeAndUsedFalseOrderByCreatedAtDesc(String target, String purpose);
}
