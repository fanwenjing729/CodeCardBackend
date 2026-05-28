package com.codecard.auth.dto;

import jakarta.validation.constraints.NotBlank;

public class OtpSendRequest {

    @NotBlank(message = "target is required")
    private String target;

    @NotBlank(message = "purpose is required")
    private String purpose; // login, register, reset

    public String getTarget() { return target; }
    public void setTarget(String target) { this.target = target; }

    public String getPurpose() { return purpose; }
    public void setPurpose(String purpose) { this.purpose = purpose; }
}
