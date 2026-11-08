package ru.tbcarus.photo_cloud_client.api;

import retrofit2.Call;
import retrofit2.http.GET;
import retrofit2.http.PATCH;
import retrofit2.http.Path;
import retrofit2.http.Query;
import ru.tbcarus.photo_cloud_client.api.models.NotificationDto;
import ru.tbcarus.photo_cloud_client.api.models.NotificationPageDto;
import ru.tbcarus.photo_cloud_client.api.models.UnreadNotificationCountDto;

/** Уведомления текущего пользователя. Состояние прочтения хранит только Server. */
public interface NotificationService {
    @GET(ApiPaths.NOTIFICATIONS)
    Call<NotificationPageDto> getNotifications(@Query("page") int page, @Query("size") int size);

    @GET(ApiPaths.NOTIFICATIONS_UNREAD_COUNT)
    Call<UnreadNotificationCountDto> getUnreadCount();

    /** Без тела: Retrofit отправляет пустое. Повтор не меняет исходный readAt. */
    @PATCH(ApiPaths.NOTIFICATION_READ)
    Call<NotificationDto> markRead(@Path("id") long id);

    /** Ответ 204 без тела. */
    @PATCH(ApiPaths.NOTIFICATIONS_READ_ALL)
    Call<Void> markAllRead();
}
