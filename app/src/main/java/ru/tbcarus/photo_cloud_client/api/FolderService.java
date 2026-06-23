package ru.tbcarus.photo_cloud_client.api;

import java.util.List;

import retrofit2.Call;
import retrofit2.http.GET;
import retrofit2.http.Path;
import ru.tbcarus.photo_cloud_client.api.models.FolderDto;

public interface FolderService {
    @GET(ApiPaths.FOLDERS_ROOT)
    Call<FolderDto> getRoot();

    @GET(ApiPaths.FOLDERS_CHILDREN)
    Call<List<FolderDto>> getChildren(@Path("id") long id);
}
