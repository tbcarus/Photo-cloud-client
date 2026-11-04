package ru.tbcarus.photo_cloud_client.api;

import java.util.List;

import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.DELETE;
import retrofit2.http.GET;
import retrofit2.http.PATCH;
import retrofit2.http.POST;
import retrofit2.http.Path;
import ru.tbcarus.photo_cloud_client.api.models.CreateBackupSourceRequest;
import ru.tbcarus.photo_cloud_client.api.models.CreateFolderRequest;
import ru.tbcarus.photo_cloud_client.api.models.FolderDto;
import ru.tbcarus.photo_cloud_client.api.models.MoveFolderRequest;
import ru.tbcarus.photo_cloud_client.api.models.RenameFolderRequest;

public interface FolderService {
    @GET(ApiPaths.FOLDERS_ROOT)
    Call<FolderDto> getRoot();

    @GET(ApiPaths.FOLDERS_CHILDREN)
    Call<List<FolderDto>> getChildren(@Path("id") long id);

    @POST(ApiPaths.FOLDERS_CAMERA)
    Call<FolderDto> getOrCreateCamera();

    @POST(ApiPaths.FOLDERS)
    Call<FolderDto> createFolder(@Body CreateFolderRequest request);

    /** Переименование с сохранением ID: Server меняет только имя папки, вложенные объекты не трогает. */
    @PATCH(ApiPaths.FOLDER_ID)
    Call<FolderDto> renameFolder(@Path("id") long id, @Body RenameFolderRequest request);

    /** Перенос USER-папки вместе с поддеревом; ID папки и вложенных объектов сохраняются. */
    @POST(ApiPaths.FOLDER_MOVE)
    Call<FolderDto> moveFolder(@Path("id") long id, @Body MoveFolderRequest request);

    /** Удаление USER-папки со всем поддеревом; ответ 204 без тела. */
    @DELETE(ApiPaths.FOLDER_ID)
    Call<Void> deleteFolder(@Path("id") long id);

    // Идемпотентно по sourceUuid: повтор с тем же UUID и именем возвращает прежнюю папку.
    @POST(ApiPaths.FOLDERS_BACKUP)
    Call<FolderDto> registerBackupSource(@Body CreateBackupSourceRequest request);

    // Идемпотентно: повтор для уже отключённого корня источника тоже отвечает 204.
    @DELETE(ApiPaths.FOLDER_BACKUP_SOURCE)
    Call<Void> disableBackupSource(@Path("id") long id);
}
