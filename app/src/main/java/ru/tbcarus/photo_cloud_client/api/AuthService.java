package ru.tbcarus.photo_cloud_client.api;

import java.util.Map;

import retrofit2.Call;

import retrofit2.http.Body;
import retrofit2.http.POST;
import ru.tbcarus.photo_cloud_client.api.models.AuthRequest;
import ru.tbcarus.photo_cloud_client.api.models.AuthResponse;
import ru.tbcarus.photo_cloud_client.api.models.LogoutRequest;
import ru.tbcarus.photo_cloud_client.api.models.RefreshTokenRequest;

public interface AuthService {
    @POST(ApiPaths.AUTH_REGISTER)
    Call<Map<String, String>> register(@Body AuthRequest request);

    @POST(ApiPaths.AUTH_LOGIN)
    Call<AuthResponse> login(@Body AuthRequest request);

    @POST(ApiPaths.AUTH_REFRESH_TOKEN)
    Call<AuthResponse> refreshToken(@Body RefreshTokenRequest token);

    @POST(ApiPaths.AUTH_LOGOUT)
    Call<Map<String, String>> logout(@Body LogoutRequest logoutRequest);
}

