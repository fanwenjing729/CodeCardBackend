package com.codecard.progress;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "user_progress")
public class UserProgress {

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @Column(columnDefinition = "jsonb", nullable = false)
    private String data;

    @Column(nullable = false)
    private int version;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        if (updatedAt == null) {
            updatedAt = Instant.now();
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }

    public String getData() { return data; }
    public void setData(String data) { this.data = data; }

    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
