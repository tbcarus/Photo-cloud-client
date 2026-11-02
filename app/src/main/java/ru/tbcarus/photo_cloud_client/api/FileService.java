package ru.tbcarus.photo_cloud_client.api;

import okhttp3.MultipartBody;
import okhttp3.RequestBody;
import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.GET;
import retrofit2.http.Multipart;
import retrofit2.http.POST;
import retrofit2.http.Part;
import retrofit2.http.Path;
import retrofit2.http.Query;
import retrofit2.http.Streaming;
import ru.tbcarus.photo_cloud_client.api.models.ChecksumExistsRequest;
import ru.tbcarus.photo_cloud_client.api.models.ChecksumExistsResponse;
import ru.tbcarus.photo_cloud_client.api.models.FileItemDto;
import ru.tbcarus.photo_cloud_client.api.models.FilePageDto;

public interface FileService {
    /** Страница прямых файлов папки. sort = null — параметр не отправляется, порядок по дате. */
    @GET(ApiPaths.FILES)
    Call<FilePageDto> getFiles(
            @Query("folderId") long folderId,
            @Query("page") int page,
            @Query("size") int size,
            @Query("sort") String sort
    );

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

    /** Байты файла потоком: без @Streaming Retrofit прочитал бы весь ответ в память. */
    @Streaming
    @GET(ApiPaths.FILES_DOWNLOAD)
    Call<ResponseBody> download(@Path("id") long id);
}
