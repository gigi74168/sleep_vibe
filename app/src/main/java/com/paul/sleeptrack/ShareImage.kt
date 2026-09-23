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
 * Définitions proposées au partage. C'est la largeur de l'image qui suit le format vidéo
 * (1920 px pour du 1080p, 3840 px pour de la 4K) ; la hauteur découle de la mise en page,
 * environ 1210 et 2420 px.
 */
enum class ShareQuality(val label: String, val widthPx: Int) {
    SD("SD · 1080p", 1920),
    HD("HD · 4K", 3840),
}

/**
 * Exporte la grille de l'année en PNG et ouvre le sélecteur de partage Android.
 * Le fichier reste dans le cache privé de l'app ; c'est le partage choisi par
 * l'utilisateur qui décide de sa destination.
 */
suspend fun shareYearImage(context: Context, year: Int, metric: Metric, data: HealthData, quality: ShareQuality) {
    // Le rendu et l'encodage PNG bloqueraient l'interface, jusqu'à quelques secondes en
    // 4K : ils se font à côté, seul l'envoi du sélecteur revient sur le fil principal.
    val uri = withContext(Dispatchers.Default) {
        val bitmap = renderYearCard(year, metric, data, Prefs.goals(context), quality.widthPx)
        val file = try {
            writePng(context, bitmap, "sleeptrack-${metric.name.lowercase()}-$year.png")
        } finally {
            // Près de 40 Mo en 4K : rendus tout de suite plutôt qu'au prochain ramassage.
            bitmap.recycle()
        }
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
