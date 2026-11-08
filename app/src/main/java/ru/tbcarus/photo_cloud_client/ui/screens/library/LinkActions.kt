package ru.tbcarus.photo_cloud_client.ui.screens.library

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent

/** Публичная ссылка в системный буфер обмена — полный адрес из ответа Server, без записи в журналы. */
fun copyLinkToClipboard(context: Context, url: String) {
    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("PhotoCloud", url))
}

/** Системный выбор приложения для отправки ссылки; приложение и адресата выбирает пользователь. */
fun shareLinkIntent(url: String): Intent = Intent.createChooser(
    Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, url)
    },
    "Поделиться ссылкой"
)

/** false — системный выбор не открылся. */
fun sendLink(context: Context, url: String): Boolean = try {
    context.startActivity(shareLinkIntent(url).apply { if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
    true
} catch (_: ActivityNotFoundException) {
    false
}
