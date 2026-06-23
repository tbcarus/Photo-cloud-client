package ru.tbcarus.photo_cloud_client.api;

import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.POST;
import ru.tbcarus.photo_cloud_client.api.models.ChecksumExistsRequest;
import ru.tbcarus.photo_cloud_client.api.models.ChecksumExistsResponse;

public interface FileService {
    @POST(ApiPaths.FILES_CHECKSUMS_EXISTS)
    Call<ChecksumExistsResponse> checksumsExist(@Body ChecksumExistsRequest request);
}
