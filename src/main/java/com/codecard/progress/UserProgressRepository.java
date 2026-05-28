package com.codecard.progress;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface UserProgressRepository extends JpaRepository<UserProgress, UUID> {
}
