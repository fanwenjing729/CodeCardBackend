package com.codecard.progress;

import com.codecard.progress.dto.ProgressSyncRequest;
import com.codecard.progress.dto.ProgressSyncResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/progress")
public class ProgressController {

    private final ProgressService progressService;

    public ProgressController(ProgressService progressService) {
        this.progressService = progressService;
    }

    @GetMapping
    public ResponseEntity<ProgressSyncResponse> getProgress(@AuthenticationPrincipal String userId) {
        ProgressSyncResponse resp = progressService.getProgress(UUID.fromString(userId));
        return ResponseEntity.ok(resp != null ? resp : emptyResponse());
    }

    @PutMapping
    public ResponseEntity<ProgressSyncResponse> putProgress(
            @AuthenticationPrincipal String userId,
            @Valid @RequestBody ProgressSyncRequest req) {
        return ResponseEntity.ok(progressService.upsertProgress(UUID.fromString(userId), req));
    }

    @PostMapping("/sync")
    public ResponseEntity<ProgressSyncResponse> syncProgress(
            @AuthenticationPrincipal String userId,
            @Valid @RequestBody ProgressSyncRequest req) {
        return ResponseEntity.ok(progressService.syncProgress(UUID.fromString(userId), req));
    }

    private ProgressSyncResponse emptyResponse() {
        ProgressSyncResponse resp = new ProgressSyncResponse();
        resp.setData(java.util.Map.of());
        resp.setVersion(3);
        resp.setMerged(false);
        return resp;
    }
}
