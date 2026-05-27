package ru.tbcarus.photo_cloud_client.api;

import java.util.Map;

import retrofit2.Call;

import retrofit2.http.Body;
import retrofit2.http.POST;
import ru.tbcarus.photo_cloud_client.api.models.AuthRequest;
import ru.tbcarus.photo_cloud_client.api.models.LoginResponseDto;
import ru.tbcarus.photo_cloud_client.api.models.LogoutRequest;
import ru.tbcarus.photo_cloud_client.api.models.RefreshResponseDto;
import ru.tbcarus.photo_cloud_client.api.models.RefreshTokenRequest;

public interface AuthService {
    @POST(ApiPaths.AUTH_REGISTER)
    Call<Map<String, String>> register(@Body AuthRequest request);

    @POST(ApiPaths.AUTH_LOGIN)
    Call<LoginResponseDto> login(@Body AuthRequest request);

    @POST(ApiPaths.AUTH_REFRESH_TOKEN)
    Call<RefreshResponseDto> refreshToken(@Body RefreshTokenRequest token);

    @POST(ApiPaths.AUTH_LOGOUT)
    Call<Map<String, String>> logout(@Body LogoutRequest logoutRequest);
}
