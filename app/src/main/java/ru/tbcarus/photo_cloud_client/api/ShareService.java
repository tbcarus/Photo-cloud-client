package ru.tbcarus.photo_cloud_client.api;

import retrofit2.Call;
import retrofit2.http.DELETE;
import retrofit2.http.GET;
import retrofit2.http.Path;
import retrofit2.http.Query;
import ru.tbcarus.photo_cloud_client.api.models.SharedPageDto;

/** Полученные USER-доступы: только чтение списка и отказ получателя. */
public interface ShareService {
    @GET(ApiPaths.SHARED_WITH_ME)
    Call<SharedPageDto> getSharedWithMe(@Query("page") int page, @Query("size") int size);

    /** Удаляет только собственный доступ получателя; ресурс владельца не меняется. Ответ 204 без тела. */
    @DELETE(ApiPaths.SHARED_WITH_ME_ID)
    Call<Void> removeShared(@Path("shareId") long shareId);
}
