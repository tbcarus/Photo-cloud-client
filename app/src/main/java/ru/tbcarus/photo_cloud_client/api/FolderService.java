package ru.tbcarus.photo_cloud_client.api;

import retrofit2.Call;
import retrofit2.http.POST;
import ru.tbcarus.photo_cloud_client.api.models.FolderDto;

public interface FolderService {
    @POST(ApiPaths.FOLDERS_CAMERA)
    Call<FolderDto> getOrCreateCamera();
}
