package ru.tbcarus.photo_cloud_client.api.models;

public class AuthResponse {
    private String accessToken;
    private String refreshToken;

    public String getAccessToken() { return accessToken; }
    public String getRefreshToken() { return refreshToken; }
}

