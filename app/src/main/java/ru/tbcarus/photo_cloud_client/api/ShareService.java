package ru.tbcarus.photo_cloud_client.api;

import java.util.List;

import okhttp3.RequestBody;
import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.DELETE;
import retrofit2.http.GET;
import retrofit2.http.PATCH;
import retrofit2.http.POST;
import retrofit2.http.Path;
import retrofit2.http.Query;
import ru.tbcarus.photo_cloud_client.api.models.GrantShareRequest;
import ru.tbcarus.photo_cloud_client.api.models.ShareDto;
import ru.tbcarus.photo_cloud_client.api.models.SharePageDto;
import ru.tbcarus.photo_cloud_client.api.models.SharedPageDto;

/** USER-доступы: полученные (список и отказ) и выданные владельцем (выдача, список, срок, отзыв). */
public interface ShareService {
    @GET(ApiPaths.SHARED_WITH_ME)
    Call<SharedPageDto> getSharedWithMe(@Query("page") int page, @Query("size") int size);

    /** Удаляет только собственный доступ получателя; ресурс владельца не меняется. Ответ 204 без тела. */
    @DELETE(ApiPaths.SHARED_WITH_ME_ID)
    Call<Void> removeShared(@Path("shareId") long shareId);

    /** Все адреса или никто: при недопустимом получателе Server не создаёт ни одного доступа. */
    @POST(ApiPaths.SHARES_USERS)
    Call<List<ShareDto>> grant(@Body GrantShareRequest request);

    /** null-параметр Retrofit не передаёт: без фильтра — все выданные доступы. */
    @GET(ApiPaths.SHARES)
    Call<SharePageDto> getShares(@Query("fileId") Long fileId, @Query("folderId") Long folderId,
                                 @Query("page") int page, @Query("size") int size);

    /** Тело собирается вручную: явный {"expiresAt":null} снимает срок, а общий Gson null-поля не пишет. */
    @PATCH(ApiPaths.SHARE_ID)
    Call<ShareDto> updateExpiry(@Path("id") long id, @Body RequestBody body);

    @DELETE(ApiPaths.SHARE_ID)
    Call<Void> revoke(@Path("id") long id);

    /** Все USER-доступы одного ресурса; доступы вложенных объектов и ссылки не затрагиваются. */
    @DELETE(ApiPaths.SHARES_USERS)
    Call<Void> revokeAll(@Query("fileId") Long fileId, @Query("folderId") Long folderId);
}
