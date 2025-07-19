package ru.tbcarus.photo_cloud_client.ui.screens.network

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider

class NetworkViewModelFactory(
    private val application: Application? = null
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(NetworkViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return NetworkViewModel(application!!) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}