package ru.tbcarus.photo_cloud_client.di

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BaseUrlProvider @Inject constructor() {
    var baseUrl: String = ""
    val isReady: Boolean get() = baseUrl.isNotBlank()
}
