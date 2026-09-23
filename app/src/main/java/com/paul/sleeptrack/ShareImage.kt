package com.paul.sleeptrack

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.core.content.FileProvider
import com.paul.sleeptrack.ui.theme.AubeTokens
import com.paul.sleeptrack.ui.theme.ThemeChoice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Définitions proposées au partage. C'est la largeur de l'image qui suit le format vidéo
 * (3840 px pour de la 4K, 15 360 px pour de la 16K) ; la hauteur découle de la mise en page,
 * environ 2420 et 9680 px.
 */
enum class ShareQuality(val label: String, val widthPx: Int) {
    SD("SD · 4K", 3840),
    HD("HD · 16K", 15_360),
}

/**
 * Au-delà, l'image ne passe plus par un bitmap entier (la 16K en demanderait près de 600 Mo) :
 * elle est dessinée et compressée par bandes.
 */
private const val MAX_BITMAP_PIXELS = 16_000_000L

/**
 * Exporte la grille de l'année en PNG et ouvre le sélecteur de partage Android.
 * Le fichier reste dans le cache privé de l'app ; c'est le partage choisi par
 * l'utilisateur qui décide de sa destination.
 */
suspend fun shareYearImage(context: Context, year: Int, metric: Metric, data: HealthData, quality: ShareQuality) {
    // Le rendu et l'encodage PNG bloqueraient l'interface, plusieurs secondes en 16K : ils se
    // font à côté, seul l'envoi du sélecteur revient sur le fil principal.
    val uri = withContext(Dispatchers.Default) {
        // En Aube, l'image garde toujours le fond clair de la maquette, même en Nuit tombée.
        val colors = if (Prefs.appearance(context).theme == ThemeChoice.AUBE) AubeTokens.Aube else AubeTokens.Classique
        val card = YearCard(year, metric, data, Prefs.goals(context), bitmapStyle(context, colors), quality.widthPx)
        val file = pngFile(context, "sleeptrack-${metric.name.lowercase()}-$year.png")
        FileOutputStream(file).buffered(1 shl 16).use { out ->
            if (card.width.toLong() * card.height <= MAX_BITMAP_PIXELS) {
                val bitmap = card.toBitmap()
                try {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                } finally {
                    // Près de 40 Mo en 4K : rendus tout de suite plutôt qu'au prochain ramassage.
                    bitmap.recycle()
                }
            } else {
                card.writePng(out)
            }
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

private fun pngFile(context: Context, name: String): File {
    val dir = File(context.cacheDir, "partage").apply { mkdirs() }
    // Un seul export à la fois : on nettoie les précédents pour ne pas laisser
    // grossir le cache au fil des partages.
    dir.listFiles()?.forEach { it.delete() }
    return File(dir, name)
}
