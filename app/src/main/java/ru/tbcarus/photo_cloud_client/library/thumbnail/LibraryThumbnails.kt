package ru.tbcarus.photo_cloud_client.library.thumbnail

import android.content.Context
import coil3.ImageLoader
import coil3.annotation.ExperimentalCoilApi
import coil3.disk.DiskCache
import coil3.intercept.Interceptor
import coil3.map.Mapper
import coil3.memory.MemoryCache
import coil3.network.HttpException
import coil3.network.cachecontrol.CacheControlCacheStrategy
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.ImageResult
import coil3.toUri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okio.Path.Companion.toOkioPath
import ru.tbcarus.photo_cloud_client.core.storage.TokenStorage
import ru.tbcarus.photo_cloud_client.di.BaseUrlProvider
import ru.tbcarus.photo_cloud_client.library.LibrarySession
import ru.tbcarus.photo_cloud_client.library.librarySessionFlow
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * Миниатюры Library: один ImageLoader на процесс, кэш в памяти и в `cacheDir/thumbnails`.
 * Запросы идут через авторизованный OkHttp-клиент (тот же AuthInterceptor и TokenAuthenticator), но со своим
 * Dispatcher: не больше [MAX_PARALLEL] одновременных запросов, обычные API этим лимитом не ограничены.
 * Смена сервера или пользователя очищает кэши и отбрасывает результаты прежнего контекста.
 */
@Singleton
class LibraryThumbnails internal constructor(
    context: Context,
    authClient: OkHttpClient,
    sessions: Flow<LibrarySession>,
    dispatcher: CoroutineDispatcher,
    cacheDir: File,
    retryInterceptor: ThumbnailRetryInterceptor = ThumbnailRetryInterceptor(),
    now: () -> Instant = Clock.System::now
) {

    @Inject
    constructor(
        @ApplicationContext context: Context,
        @Named("auth") authClient: OkHttpClient,
        baseUrlProvider: BaseUrlProvider,
        tokenStorage: TokenStorage
    ) : this(
        context,
        authClient,
        librarySessionFlow(baseUrlProvider, tokenStorage),
        Dispatchers.IO,
        File(context.cacheDir, CACHE_DIR)
    )

    companion object {
        const val MAX_PARALLEL = 3
        private const val CACHE_DIR = "thumbnails"
        private const val DISK_CACHE_BYTES = 50L * 1024 * 1024
        private const val MEMORY_CACHE_PERCENT = 0.15

        // Ответ окончательный для этого содержимого: повтор при новом показе строки ничего не изменит.
        private val PERMANENT_FAILURES = setOf(404, 415, 422)
    }

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val _scope = MutableStateFlow<ThumbnailScope?>(null)

    /** Текущий контекст миниатюр; null — нет сервера или сессии, миниатюры не запрашиваются. */
    val currentScope: StateFlow<ThumbnailScope?> = _scope.asStateFlow()

    private val failures = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    private var sessionSeen = false

    val client: OkHttpClient = authClient.newBuilder()
        .dispatcher(Dispatcher().apply {
            maxRequests = MAX_PARALLEL
            maxRequestsPerHost = MAX_PARALLEL
        })
        .addInterceptor(retryInterceptor)
        .build()

    @OptIn(ExperimentalCoilApi::class)
    val imageLoader: ImageLoader = ImageLoader.Builder(context)
        .memoryCache { MemoryCache.Builder().maxSizePercent(context, MEMORY_CACHE_PERCENT).build() }
        .diskCache { DiskCache.Builder().directory(cacheDir.toOkioPath()).maxSizeBytes(DISK_CACHE_BYTES).build() }
        .components {
            add(ScopeGuard())
            add(Mapper<Thumbnail, coil3.Uri> { data, _ -> data.url.toUri() })
            add(OkHttpNetworkFetcherFactory(callFactory = { client }, cacheStrategy = { CacheControlCacheStrategy(now) }))
        }
        .build()

    init {
        scope.launch { sessions.collect(::onSession) }
    }

    fun isFailed(thumbnail: Thumbnail): Boolean = thumbnail.cacheKey in failures

    fun request(context: Context, thumbnail: Thumbnail): ImageRequest =
        ImageRequest.Builder(context)
            .data(thumbnail)
            .memoryCacheKey(thumbnail.cacheKey)
            .diskCacheKey(thumbnail.cacheKey)
            .listener(onError = { _, result -> onError(thumbnail, result) })
            .build()

    private fun onError(thumbnail: Thumbnail, result: ErrorResult) {
        val code = (result.throwable as? HttpException)?.response?.code
        if (code in PERMANENT_FAILURES && thumbnail.scope == _scope.value) failures += thumbnail.cacheKey
    }

    // Сессии приходят последовательно из одного collect.
    private fun onSession(session: LibrarySession) {
        val newScope = ThumbnailScope.of(session)
        if (sessionSeen && newScope == _scope.value) return
        val first = !sessionSeen
        sessionSeen = true
        // Сначала контекст: ScopeGuard сразу отбрасывает запросы прежнего, затем очищаются кэши.
        _scope.value = newScope
        failures.clear()
        // Первая сессия процесса не очищает диск: записи прошлого запуска либо её же, либо под ключом другого контекста.
        if (first) return
        imageLoader.memoryCache?.clear()
        imageLoader.diskCache?.clear()
    }

    /**
     * Отбрасывает запросы прежнего контекста: и до сети, и результат, полученный уже после смены сервера
     * или пользователя. Иначе поздний ответ попал бы в строку нового контекста.
     */
    private inner class ScopeGuard : Interceptor {
        override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
            val thumbnail = chain.request.data as? Thumbnail ?: return chain.proceed()
            if (thumbnail.scope != _scope.value) return stale(chain)
            val result = chain.proceed()
            return if (thumbnail.scope != _scope.value) stale(chain) else result
        }

        private fun stale(chain: Interceptor.Chain) =
            ErrorResult(null, chain.request, IllegalStateException("Thumbnail of a previous session"))
    }
}
