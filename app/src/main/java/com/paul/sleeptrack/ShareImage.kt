package com.paul.sleeptrack

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Exporte la grille de l'année en PNG et ouvre le sélecteur de partage Android.
 * Le fichier reste dans le cache privé de l'app ; c'est le partage choisi par
 * l'utilisateur qui décide de sa destination.
 */
suspend fun shareYearImage(context: Context, year: Int, metric: Metric, data: HealthData) {
    // Le rendu et l'encodage PNG bloquaient l'interface le temps de plusieurs images :
    // ils se font à côté, seul l'envoi du sélecteur revient sur le fil principal.
    val uri = withContext(Dispatchers.Default) {
        val bitmap = renderYearCard(year, metric, data)
        val file = writePng(context, bitmap, "sommeil-${metric.name.lowercase()}-$year.png")
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "image/png"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_TITLE, "${metric.label} $year")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(
        Intent.createChooser(intent, "Partager ${metric.label.lowercase()} $year")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    )
}

private fun writePng(context: Context, bitmap: Bitmap, name: String): File {
    val dir = File(context.cacheDir, "partage").apply { mkdirs() }
    // Un seul export à la fois : on nettoie les précédents pour ne pas laisser
    // grossir le cache au fil des partages.
    dir.listFiles()?.forEach { it.delete() }
    val file = File(dir, name)
    FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    return file
}
