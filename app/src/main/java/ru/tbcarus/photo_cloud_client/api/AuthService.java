package ru.tbcarus.photo_cloud_client.api;

import android.telecom.Call;

import retrofit2.http.Body;
import retrofit2.http.GET;
import retrofit2.http.Header;
import retrofit2.http.POST;
import ru.tbcarus.photo_cloud_client.api.models.AuthRequest;
import ru.tbcarus.photo_cloud_client.api.models.AuthResponse;
import ru.tbcarus.photo_cloud_client.api.models.RefreshTokenRequest;

public interface AuthService {
    @GET("api/test")
    Call<String> testServer();

    @POST("api/auth/register")
    Call<Void> register(@Body AuthRequest request);

    @POST("api/auth/login")
    Call<AuthResponse> login(@Body AuthRequest request);

    @POST("api/user/refresh-token")
    Call<AuthResponse> refreshToken(@Body RefreshTokenRequest token);

    @GET("api/test/auth")
    Call<String> testAuth(@Header("Authorization") String token);
}

