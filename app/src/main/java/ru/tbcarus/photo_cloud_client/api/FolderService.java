package ru.tbcarus.photo_cloud_client.api;

import java.util.List;

import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.GET;
import retrofit2.http.POST;
import retrofit2.http.Path;
import ru.tbcarus.photo_cloud_client.api.models.CreateBackupSourceRequest;
import ru.tbcarus.photo_cloud_client.api.models.CreateFolderRequest;
import ru.tbcarus.photo_cloud_client.api.models.FolderDto;

public interface FolderService {
    @GET(ApiPaths.FOLDERS_ROOT)
    Call<FolderDto> getRoot();

    @GET(ApiPaths.FOLDERS_CHILDREN)
    Call<List<FolderDto>> getChildren(@Path("id") long id);

    @POST(ApiPaths.FOLDERS_CAMERA)
    Call<FolderDto> getOrCreateCamera();

    @POST(ApiPaths.FOLDERS)
    Call<FolderDto> createFolder(@Body CreateFolderRequest request);

    // Идемпотентно по sourceUuid: повтор с тем же UUID и именем возвращает прежнюю папку.
    @POST(ApiPaths.FOLDERS_BACKUP)
    Call<FolderDto> registerBackupSource(@Body CreateBackupSourceRequest request);
}
