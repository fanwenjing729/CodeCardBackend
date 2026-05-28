package com.codecard.progress.dto;

import java.time.Instant;
import java.util.Map;

public class ProgressSyncResponse {

    private Map<String, Object> data;
    private int version;
    private Instant updatedAt;
    private boolean merged;

    public Map<String, Object> getData() { return data; }
    public void setData(Map<String, Object> data) { this.data = data; }

    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public boolean isMerged() { return merged; }
    public void setMerged(boolean merged) { this.merged = merged; }
}
