package com.impossi8le.vpnapp.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File

private const val APK_MIME = "application/vnd.android.package-archive"

/**
 * Установка скачанного APK.
 *
 * Подпись не проверяем вручную: система сама отвергнет APK с чужим ключом, и
 * установка «поверх» просто не пройдёт — отдельная проверка была бы дублем
 * системной и создавала бы ложное чувство, что мы что-то контролируем.
 *
 * Наша часть — отдать файл через FileProvider и, если пользователь запретил
 * установку из неизвестных источников, показать ему, где это включить:
 * молчаливая кнопка хуже честного отказа.
 */
class ApkInstaller(private val context: Context) {

    /** Разрешена ли установка из этого приложения. */
    fun canInstall(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            // minSdk 26, то есть O и выше всегда, — но ветка оставлена, чтобы
            // поведение не менялось незаметно при понижении minSdk.
            true
        }

    /** Экран настроек «Установка неизвестных приложений» для этого пакета. */
    fun unknownSourcesIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).setData(
            Uri.parse("package:${context.packageName}"),
        )

    /**
     * Намерение установки. Отдельным методом, а не `install()` с побочным
     * эффектом: запускать активность должен экран — только у него есть
     * `ActivityResultLauncher` и обработка `ActivityNotFoundException`.
     */
    fun install(apk: File): Intent {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apk,
        )
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, APK_MIME)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}
