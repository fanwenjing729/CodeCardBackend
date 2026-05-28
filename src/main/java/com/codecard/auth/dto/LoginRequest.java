package com.codecard.auth.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;

public class LoginRequest {

    private String email;
    private String phone;
    @NotBlank(message = "password is required")
    private String password;

    @AssertTrue(message = "email or phone is required")
    public boolean hasIdentity() {
        return (email != null && !email.isBlank()) || (phone != null && !phone.isBlank());
    }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
}
