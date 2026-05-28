package com.codecard.auth.dto;

public class AuthResponse {

    private UserProfile user;
    private String accessToken;
    private String refreshToken;
    private Boolean isNewUser;

    public static class UserProfile {
        private String id;
        private String email;
        private String phone;
        private String displayId;
        private String avatarUrl;

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }

        public String getEmail() { return email; }
        public void setEmail(String email) { this.email = email; }

        public String getPhone() { return phone; }
        public void setPhone(String phone) { this.phone = phone; }

        public String getDisplayId() { return displayId; }
        public void setDisplayId(String displayId) { this.displayId = displayId; }

        public String getAvatarUrl() { return avatarUrl; }
        public void setAvatarUrl(String avatarUrl) { this.avatarUrl = avatarUrl; }
    }

    public UserProfile getUser() { return user; }
    public void setUser(UserProfile user) { this.user = user; }

    public String getAccessToken() { return accessToken; }
    public void setAccessToken(String accessToken) { this.accessToken = accessToken; }

    public String getRefreshToken() { return refreshToken; }
    public void setRefreshToken(String refreshToken) { this.refreshToken = refreshToken; }

    public Boolean getIsNewUser() { return isNewUser; }
    public void setIsNewUser(Boolean isNewUser) { this.isNewUser = isNewUser; }
}
