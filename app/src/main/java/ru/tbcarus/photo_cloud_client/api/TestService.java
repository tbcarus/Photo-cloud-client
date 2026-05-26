package ru.tbcarus.photo_cloud_client.api;

import retrofit2.Call;
import retrofit2.http.GET;
import ru.tbcarus.photo_cloud_client.api.models.TestResponse;

public interface TestService {
    @GET(ApiPaths.TEST)
    Call<TestResponse> testServer();

    @GET(ApiPaths.TEST_AUTH)
    Call<TestResponse> testAuth();
}
