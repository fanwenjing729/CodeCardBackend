package com.codecard.auth.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Size;

public class RegisterRequest {

    private String email;
    private String phone;

    @Size(min = 6, message = "password must be at least 6 characters")
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
