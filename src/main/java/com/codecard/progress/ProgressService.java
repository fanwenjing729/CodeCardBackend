package com.codecard.progress;

import com.codecard.progress.dto.ProgressSyncRequest;
import com.codecard.progress.dto.ProgressSyncResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Service
public class ProgressService {

    private final UserProgressRepository progressRepo;
    private final ObjectMapper mapper;

    public ProgressService(UserProgressRepository progressRepo, ObjectMapper mapper) {
        this.progressRepo = progressRepo;
        this.mapper = mapper;
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

        try {
            progress.setData(mapper.writeValueAsString(req.getData()));
        } catch (JsonProcessingException e) {
            throw new RuntimeException("failed to serialize progress data", e);
        }
        progress.setVersion(req.getVersion());
        progress.setUpdatedAt(Instant.now());
        progressRepo.save(progress);

        return toResponse(progress);
    }

    @Transactional
    public ProgressSyncResponse syncProgress(UUID userId, ProgressSyncRequest req) {
        return progressRepo.findById(userId)
                .map(remote -> {
                    // Return remote data; client handles the merge
                    ProgressSyncResponse resp = toResponse(remote);
                    resp.setMerged(true);
                    return resp;
                })
                .orElseGet(() -> {
                    // No remote data — save client data
                    ProgressSyncResponse resp = upsertProgress(userId, req);
                    resp.setMerged(false);
                    return resp;
                });
    }

    @SuppressWarnings("unchecked")
    private ProgressSyncResponse toResponse(UserProgress progress) {
        ProgressSyncResponse resp = new ProgressSyncResponse();
        try {
            resp.setData(mapper.readValue(progress.getData(), Map.class));
        } catch (JsonProcessingException e) {
            resp.setData(Map.of());
        }
        resp.setVersion(progress.getVersion());
        resp.setUpdatedAt(progress.getUpdatedAt());
        resp.setMerged(false);
        return resp;
    }
}
