package ru.tbcarus.photo_cloud_client.api;

import okhttp3.MultipartBody;
import okhttp3.RequestBody;
import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.Multipart;
import retrofit2.http.POST;
import retrofit2.http.Part;
import ru.tbcarus.photo_cloud_client.api.models.ChecksumExistsRequest;
import ru.tbcarus.photo_cloud_client.api.models.ChecksumExistsResponse;
import ru.tbcarus.photo_cloud_client.api.models.FileItemDto;

public interface FileService {
    @POST(ApiPaths.FILES_CHECKSUMS_EXISTS)
    Call<ChecksumExistsResponse> checksumsExist(@Body ChecksumExistsRequest request);

    /**
     * Upload одного файла. Используется явный endpoint /files/upload (не legacy /files).
     * folderId может быть null — тогда part не отправляется и сервер сам направит IMAGE в CAMERA.
     */
    @Multipart
    @POST(ApiPaths.FILES_UPLOAD)
    Call<FileItemDto> uploadFile(
            @Part MultipartBody.Part file,
            @Part("folderId") RequestBody folderId
    );
}
