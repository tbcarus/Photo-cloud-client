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
import ru.tbcarus.photo_cloud_client.api.models.CreateLinkRequest;
import ru.tbcarus.photo_cloud_client.api.models.GrantShareRequest;
import ru.tbcarus.photo_cloud_client.api.models.LinkPageDto;
import ru.tbcarus.photo_cloud_client.api.models.LinkShareDto;
import ru.tbcarus.photo_cloud_client.api.models.ShareDto;
import ru.tbcarus.photo_cloud_client.api.models.SharePageDto;
import ru.tbcarus.photo_cloud_client.api.models.SharedPageDto;

/**
 * USER-доступы: полученные (список и отказ) и выданные владельцем (выдача, список, срок, отзыв).
 * Публичные ссылки владельца: создание, список, срок, отзыв; USER-доступы ими не затрагиваются.
 */
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

    /** Каждый вызов создаёт новую независимую ссылку. */
    @POST(ApiPaths.SHARES_LINKS)
    Call<LinkShareDto> createLink(@Body CreateLinkRequest request);

    @GET(ApiPaths.SHARES_LINKS)
    Call<LinkPageDto> getLinks(@Query("fileId") Long fileId, @Query("folderId") Long folderId,
                               @Query("page") int page, @Query("size") int size);

    /** Тело — как у updateExpiry: явный {"expiresAt":null} снимает срок. */
    @PATCH(ApiPaths.SHARE_LINK_ID)
    Call<LinkShareDto> updateLinkExpiry(@Path("id") long id, @Body RequestBody body);

    @DELETE(ApiPaths.SHARE_LINK_ID)
    Call<Void> revokeLink(@Path("id") long id);

    /** Все ссылки одного ресурса; USER-доступы не затрагиваются. */
    @DELETE(ApiPaths.SHARES_LINKS)
    Call<Void> revokeAllLinks(@Query("fileId") Long fileId, @Query("folderId") Long folderId);
}
