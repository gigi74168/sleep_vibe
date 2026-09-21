package com.paul.sleeptrack

import android.util.Log
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.SystemInstruction
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import com.google.mlkit.genai.prompt.generationConfig
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

private const val TAG = "GenAi"

/**
 * Gemini Nano, en local, par les ML Kit GenAI APIs (Prompt API).
 *
 * Trois règles tiennent tout le fichier :
 *
 * 1. **Le modèle rédige, il ne compte pas.** Tous les chiffres envoyés sont déjà calculés
 *    par l'app ([statTiles], [streaksOf], [weekAverages], [correlationInsight]). Le prompt
 *    lui interdit d'en inventer ou d'en recalculer. Sur des données de santé, une moyenne
 *    hallucinée serait pire que pas de commentaire du tout.
 * 2. **Rien ne s'affiche avant [NanoStatus.READY].** Sans ça l'utilisateur voit des erreurs
 *    AICore brutes sur un appareil non supporté.
 * 3. **Uniquement au premier plan.** AICore refuse l'inférence en arrière-plan
 *    (`BACKGROUND_USE_BLOCKED`), ce qui exclut [ReminderReceiver] et le widget.
 *
 * Ces APIs sont en Beta : pas de SLA, et les signatures peuvent changer d'une version à
 * l'autre. Les conditions d'utilisation sont celles des ML Kit GenAI API Additional Terms.
 */
enum class NanoStatus {
    /** Appareil non supporté, bootloader déverrouillé, ou config AICore pas encore là. */
    UNSUPPORTED,
    DOWNLOADABLE,
    DOWNLOADING,
    READY,
}

class Nano : AutoCloseable {

    // getClient ne lève rien sur un appareil non supporté : seul status() fait foi.
    // Config vide : on garde le modèle stable et complet par défaut, ce que la doc
    // recommande en production (le preview demande l'AICore Developer Preview).
    private val model by lazy { Generation.getClient(generationConfig { }) }

    suspend fun status(): NanoStatus = runCatching {
        when (model.checkStatus()) {
            FeatureStatus.AVAILABLE -> NanoStatus.READY
            FeatureStatus.DOWNLOADING -> NanoStatus.DOWNLOADING
            FeatureStatus.DOWNLOADABLE -> NanoStatus.DOWNLOADABLE
            else -> NanoStatus.UNSUPPORTED
        }
    }.getOrElse {
        Log.w(TAG, "checkStatus a échoué", it)
        NanoStatus.UNSUPPORTED
    }

    /**
     * Télécharge Nano. [onProgress] reçoit (octets reçus, octets attendus) ; le total vaut
     * 0 tant qu'AICore ne l'a pas annoncé.
     */
    suspend fun download(onProgress: (Long, Long) -> Unit = { _, _ -> }): Boolean {
        var total = 0L
        var completed = false
        runCatching {
            model.download().collect { status ->
                when (status) {
                    is DownloadStatus.DownloadStarted -> total = status.bytesToDownload
                    is DownloadStatus.DownloadProgress -> onProgress(status.totalBytesDownloaded, total)
                    is DownloadStatus.DownloadCompleted -> completed = true
                    is DownloadStatus.DownloadFailed -> Log.w(TAG, "téléchargement échoué", status.e)
                    else -> Unit
                }
            }
        }.onFailure { Log.w(TAG, "download a échoué", it) }
        return completed
    }

    /**
     * Une réponse, ou null si l'inférence a échoué. Les appelants retombent tous sur le
     * texte calculé par l'app : une panne de Nano ne doit jamais vider un écran.
     */
    private suspend fun generate(
        instruction: String,
        prompt: String,
        maxTokens: Int,
        temp: Float,
    ): String? = runCatching {
        val request = generateContentRequest(SystemInstruction(instruction), TextPart(prompt)) {
            temperature = temp
            // Même question, même réponse d'un lancement à l'autre : un commentaire qui
            // change à chaque ouverture donnerait l'impression que les données bougent.
            seed = 1789
            maxOutputTokens = maxTokens
            // > 1 renvoie une erreur sur certains appareils en preview.
            candidateCount = 1
        }
        model.generateContent(request).candidates.firstOrNull()?.text?.trim()?.takeIf { it.isNotEmpty() }
    }.getOrElse { e ->
        // Les codes utiles (BUSY, quota batterie, BACKGROUND_USE_BLOCKED) sont dans
        // errorCode ; on les journalise sans s'appuyer sur des constantes qui peuvent
        // être renommées entre deux betas.
        if (e is GenAiException) Log.w(TAG, "inférence refusée (code ${e.errorCode})", e)
        else Log.w(TAG, "inférence échouée", e)
        null
    }

    override fun close() {
        runCatching { model.close() }
    }

    // ------------------------------------------------------------- Commentaire

    suspend fun comment(metric: Metric, data: HealthData, goalMinutes: Int, year: Int): String? =
        generate(COMMENT_INSTRUCTION, metricFacts(metric, data, goalMinutes, year), maxTokens = 160, temp = 0.3f)
}

// ------------------------------------------------------------------- Consignes

// Courtes volontairement : les system instructions consomment le contexte, et la doc
// recommande de rester sous 150 mots.

private const val COMMENT_INSTRUCTION =
    "Tu commentes des statistiques de santé déjà calculées. Écris en français, deux " +
        "phrases maximum, ton factuel et neutre. N'utilise que les chiffres fournis : " +
        "n'en invente aucun, n'en recalcule aucun, n'en cite aucun qui ne soit dans le " +
        "texte. Aucun conseil médical, aucun diagnostic, aucune recommandation. Pas de " +
        "liste, pas de titre, pas d'emoji. Dis ce que le croisement des chiffres montre, " +
        "ne les répète pas un par un."

// ---------------------------------------------------------------------- Faits

/**
 * Les chiffres de la métrique affichée, en texte compact. Environ 150 tokens : très loin
 * de la limite d'entrée (4000), donc pas de troncature à prévoir.
 */
fun metricFacts(metric: Metric, data: HealthData, goalMinutes: Int, year: Int): String {
    val series = data.series(metric)
    val scale = scaleFor(metric, data)
    return buildString {
        appendLine("Métrique : ${metric.detailLabel.lowercase()} (année $year).")
        if (metric == Metric.SLEEP) {
            appendLine("Objectif : ${formatDuration(Duration.ofMinutes(goalMinutes.toLong()))} par nuit.")
        }
        appendLine("Jours enregistrés : ${series.size}.")

        statTiles(metric, data, scale).forEach { appendLine("${it.label} : ${it.value}.") }

        streakTiles(metric, data, goalMinutes, year)?.let { (current, best) ->
            appendLine("${current.label} : ${current.value}.")
            appendLine("${best.label} : ${best.value}.")
        }

        val averages = weekAverages(series)
        if (averages.size == 7 && series.size >= 21) {
            val low = averages.minBy { it.value }
            val high = averages.maxBy { it.value }
            appendLine("Jour de la semaine le plus haut : ${dayName(high.key)} (${metric.format(high.value)}).")
            appendLine("Jour de la semaine le plus bas : ${dayName(low.key)} (${metric.format(low.value)}).")
        }

        // Le lien pas / sommeil n'a de sens que sur l'onglet sommeil ou pas.
        if (metric == Metric.SLEEP || metric == Metric.STEPS) {
            correlationInsight(data)?.let { insight ->
                appendLine(
                    "Lien entre les pas d'une journée et la nuit qui suit : " +
                        "r = %.2f sur %d paires (%s).".format(
                            Locale.FRENCH, insight.r, insight.count, insight.strength.lowercase(),
                        )
                )
                val active = insight.afterActive
                val quiet = insight.afterQuiet
                if (active != null && quiet != null) {
                    appendLine(
                        "Sommeil après une journée à 8 000 pas ou plus : ${formatDuration(active)} ; " +
                            "après une journée plus calme : ${formatDuration(quiet)}."
                    )
                }
            }
        }
    }
}

private fun dayName(day: DayOfWeek): String = day.getDisplayName(TextStyle.FULL, Locale.FRENCH)
