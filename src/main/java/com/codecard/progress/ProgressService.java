package com.codecard.progress;

import com.codecard.progress.dto.ProgressSyncRequest;
import com.codecard.progress.dto.ProgressSyncResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Service
public class ProgressService {

    private final UserProgressRepository progressRepo;

    public ProgressService(UserProgressRepository progressRepo) {
        this.progressRepo = progressRepo;
    }

    public ProgressSyncResponse getProgress(UUID userId) {
        return progressRepo.findById(userId)
                .map(this::toResponse)
                .orElse(null);
    }

    @Transactional
    public ProgressSyncResponse upsertProgress(UUID userId, ProgressSyncRequest req) {
        UserProgress progress = progressRepo.findById(userId).orElse(new UserProgress());
        progress.setUserId(userId);
        progress.setData(req.getData());
        progress.setVersion(req.getVersion());
        progress.setUpdatedAt(Instant.now());
        progressRepo.save(progress);

        return toResponse(progress);
    }

    @Transactional
    public ProgressSyncResponse syncProgress(UUID userId, ProgressSyncRequest req) {
        return progressRepo.findById(userId)
                .map(remote -> {
                    if (req.getVersion() > remote.getVersion()) {
                        // Client has newer data — overwrite
                        remote.setData(req.getData());
                        remote.setVersion(req.getVersion());
                        remote.setUpdatedAt(Instant.now());
                        progressRepo.save(remote);
                        ProgressSyncResponse resp = toResponse(remote);
                        resp.setMerged(false);
                        return resp;
                    } else if (req.getVersion() < remote.getVersion()) {
                        // Server has newer data — return server version for client merge
                        ProgressSyncResponse resp = toResponse(remote);
                        resp.setMerged(true);
                        return resp;
                    }
                    // Same version — no conflict
                    ProgressSyncResponse resp = toResponse(remote);
                    resp.setMerged(false);
                    return resp;
                })
                .orElseGet(() -> {
                    // No remote data — save client data
                    UserProgress progress = new UserProgress();
                    progress.setUserId(userId);
                    progress.setData(req.getData());
                    progress.setVersion(req.getVersion());
                    progress.setUpdatedAt(Instant.now());
                    progressRepo.save(progress);
                    ProgressSyncResponse resp = toResponse(progress);
                    resp.setMerged(false);
                    return resp;
                });
    }

    private ProgressSyncResponse toResponse(UserProgress progress) {
        ProgressSyncResponse resp = new ProgressSyncResponse();
        Map<String, Object> data = progress.getData();
        resp.setData(data != null ? data : Map.of());
        resp.setVersion(progress.getVersion());
        resp.setUpdatedAt(progress.getUpdatedAt());
        resp.setMerged(false);
        return resp;
    }
}
