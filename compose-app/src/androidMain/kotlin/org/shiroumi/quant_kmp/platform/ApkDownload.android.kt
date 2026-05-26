package org.shiroumi.quant_kmp.platform

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment

@Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
actual fun downloadApk(url: String) {
    val context = org.shiroumi.quant_kmp.applicationContext
    if (context == null) {
        android.util.Log.w("ApkDownload", "Application context not initialized")
        return
    }

    val request = DownloadManager.Request(Uri.parse(url)).apply {
        setTitle("quant-app.apk")
        setDescription("正在下载 APK...")
        setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "quant-app.apk")
        setMimeType("application/vnd.android.package-archive")
    }

    val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    downloadManager.enqueue(request)
}
