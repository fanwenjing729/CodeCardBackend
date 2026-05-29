package com.codecard.auth;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface OtpCodeRepository extends JpaRepository<OtpCode, UUID> {
    Optional<OtpCode> findTopByTargetAndPurposeAndUsedFalseOrderByCreatedAtDesc(String target, String purpose);

    @Modifying
    @Query("DELETE FROM OtpCode o WHERE o.used = true OR o.expiresAt < :now")
    int deleteUsedOrExpired(Instant now);
}
