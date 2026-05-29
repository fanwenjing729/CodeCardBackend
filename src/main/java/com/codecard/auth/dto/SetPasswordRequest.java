package com.codecard.auth.dto;

import jakarta.validation.constraints.Size;

public class SetPasswordRequest {

    @Size(min = 8, message = "password must be at least 8 characters")
    private String password;

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
}
