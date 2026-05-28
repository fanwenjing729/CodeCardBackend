package com.codecard.auth.dto;

import jakarta.validation.constraints.NotBlank;

public class OtpVerifyRequest {

    @NotBlank(message = "target is required")
    private String target;

    @NotBlank(message = "code is required")
    private String code;

    @NotBlank(message = "purpose is required")
    private String purpose;

    public String getTarget() { return target; }
    public void setTarget(String target) { this.target = target; }

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }

    public String getPurpose() { return purpose; }
    public void setPurpose(String purpose) { this.purpose = purpose; }
}
