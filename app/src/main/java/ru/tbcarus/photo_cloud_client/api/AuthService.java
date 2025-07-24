package ru.tbcarus.photo_cloud_client.api;

import java.util.Map;

import retrofit2.Call;

import retrofit2.Response;
import retrofit2.http.Body;
import retrofit2.http.GET;
import retrofit2.http.Header;
import retrofit2.http.POST;
import ru.tbcarus.photo_cloud_client.api.models.AuthRequest;
import ru.tbcarus.photo_cloud_client.api.models.AuthResponse;
import ru.tbcarus.photo_cloud_client.api.models.RefreshTokenRequest;
import ru.tbcarus.photo_cloud_client.api.models.TestResponse;

public interface AuthService {
    @GET("api/test")
    Call<TestResponse> testServer();

    @POST("api/auth/register")
    Response<Map<String, String>> register(@Body AuthRequest request);

    @POST("api/auth/login")
    Response<AuthResponse> login(@Body AuthRequest request);

    @POST("api/user/refresh-token")
    Response<AuthResponse> refreshToken(@Body RefreshTokenRequest token);

    @GET("api/test/auth")
    Call<TestResponse> testAuth(@Header("Authorization") String token);
}

