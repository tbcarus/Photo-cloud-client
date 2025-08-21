package ru.tbcarus.photo_cloud_client.network

class AuthInterceptor(private val storage: TokenStorage) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val tokens = storage.getTokens()
        if (tokens == null) return chain.proceed(original)
        val authed = original.newBuilder()
            .header("Authorization", "Bearer ${tokens.accessToken}")
            .build()
        return chain.proceed(authed)
    }
}
