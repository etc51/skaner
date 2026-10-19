package ru.skaner.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/** Отдаёт файлы в любое приложение через системное «Поделиться». */
fun shareFiles(context: Context, files: List<File>, mime: String, title: String) {
    if (files.isEmpty()) return
    val uris = ArrayList<Uri>(files.map { FileProvider.getUriForFile(context, "${context.packageName}.files", it) })
    val intent = if (uris.size == 1) {
        Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
    }
    intent.type = mime
    intent.putExtra(Intent.EXTRA_SUBJECT, title)
    // Без ClipData часть приложений не получает права на чтение файла.
    intent.clipData = ClipData.newRawUri(title, uris[0]).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(intent, title).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
}
