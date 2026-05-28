package com.codecard.progress.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.Map;

public class ProgressSyncRequest {

    @NotNull(message = "data is required")
    private Map<String, Object> data;

    @Positive(message = "version must be positive")
    private int version;

    public Map<String, Object> getData() { return data; }
    public void setData(Map<String, Object> data) { this.data = data; }

    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }
}
