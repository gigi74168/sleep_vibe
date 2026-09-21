package com.paul.sleeptrack

import android.content.Context
import android.util.Log
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.SystemInstruction
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import com.google.mlkit.genai.prompt.generationConfig
import org.json.JSONObject
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
 *    (`BACKGROUND_USE_BLOCKED`), ce qui exclut [ReminderReceiver] et le widget. D'où le
 *    cache de [WeeklyBrief].
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

    // ----------------------------------------------------------- Résumé hebdo

    suspend fun weeklyBrief(data: HealthData, today: LocalDate = LocalDate.now()): String? {
        // Sans nuit récente il n'y a rien à résumer, et surtout rien à mettre en cache :
        // l'écran des réglages peut très bien être ouvert sur une année passée.
        if (data.nights.keys.none { it > today.minusDays(7) }) return null
        return generate(WEEKLY_INSTRUCTION, weekFacts(data, today), maxTokens = 140, temp = 0.3f)
    }

    // ------------------------------------------------------ Question de grille

    /**
     * Traduit une question en français en une plage à surligner. Le modèle ne lit aucune
     * donnée de santé ici : il ne reçoit que la date du jour et les années disponibles, et
     * ne renvoie que des dates. Une réponse mal formée devient null, jamais un à-peu-près.
     */
    suspend fun askGrid(question: String, years: List<Int>, today: LocalDate = LocalDate.now()): GridQuery? {
        if (question.isBlank() || years.isEmpty()) return null
        val prompt = buildString {
            appendLine("Aujourd'hui : $today (${dayName(today.dayOfWeek)}).")
            appendLine("Années disponibles : ${years.sorted().joinToString(", ")}.")
            appendLine("Question : ${question.trim().take(200)}")
        }
        val raw = generate(ASK_INSTRUCTION, prompt, maxTokens = 120, temp = 0.1f) ?: return null
        return parseGridQuery(raw, years)
    }
}

/** Une métrique et une plage de dates : de quoi piloter la grille, rien de plus. */
data class GridQuery(val metric: Metric, val from: LocalDate, val to: LocalDate) {
    val range: ClosedRange<LocalDate> get() = from..to
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

private const val WEEKLY_INSTRUCTION =
    "Tu rédiges le corps d'une notification de résumé hebdomadaire, en français. Deux " +
        "phrases maximum, 250 caractères maximum, ton factuel. N'utilise que les chiffres " +
        "fournis : n'en invente aucun, n'en recalcule aucun. Aucun conseil médical, " +
        "aucune recommandation. Pas de titre, pas de liste, pas d'emoji."

private const val ASK_INSTRUCTION =
    "Tu traduis une question en français en un objet JSON, et rien d'autre. Réponds " +
        "uniquement par le JSON, sans phrase autour et sans bloc de code. Format exact : " +
        "{\"metric\":\"SLEEP\",\"from\":\"AAAA-MM-JJ\",\"to\":\"AAAA-MM-JJ\"}. metric vaut " +
        "SLEEP pour le sommeil, STEPS pour les pas, HEART pour le cœur au repos, WEIGHT " +
        "pour le poids, SCREEN pour le temps d'écran. from et to sont des dates réelles, from avant ou égale à to. Tu ne " +
        "connais aucune donnée de santé : tu ne fais que convertir une période en dates."

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

/** Les chiffres de la semaine écoulée, pour la notification du dimanche. */
fun weekFacts(data: HealthData, today: LocalDate = LocalDate.now()): String {
    val week = data.nights.filterKeys { it > today.minusDays(7) }.values
    val previous = data.nights.filterKeys { it <= today.minusDays(7) && it > today.minusDays(14) }.values
    val steps = data.steps.filterKeys { it > today.minusDays(7) }.values
    val heart = data.heart.filterKeys { it > today.minusDays(7) }.values

    return buildString {
        if (week.isEmpty()) {
            appendLine("Aucune nuit enregistrée cette semaine.")
            return@buildString
        }
        val average = Duration.ofSeconds(week.sumOf { it.seconds } / week.size)
        appendLine("Nuits enregistrées cette semaine : ${week.size}.")
        appendLine("Sommeil moyen cette semaine : ${formatDuration(average)}.")
        if (previous.isNotEmpty()) {
            val before = Duration.ofSeconds(previous.sumOf { it.seconds } / previous.size)
            val delta = average.minus(before).toMinutes()
            appendLine("Semaine précédente : ${formatDuration(before)}.")
            appendLine("Écart : ${if (delta >= 0) "+" else ""}$delta minutes.")
        }
        appendLine("Nuit la plus longue : ${formatDuration(week.max())}.")
        appendLine("Nuit la plus courte : ${formatDuration(week.min())}.")
        if (steps.isNotEmpty()) appendLine("Pas par jour : ${formatSteps(steps.sum() / steps.size)}.")
        if (heart.isNotEmpty()) appendLine("Cœur au repos moyen : %.0f bpm.".format(heart.average()))
    }
}

private fun dayName(day: DayOfWeek): String = day.getDisplayName(TextStyle.FULL, Locale.FRENCH)

// -------------------------------------------------------------------- Parsing

/**
 * Lit le JSON renvoyé par le modèle. Tout ce qui n'est pas exactement une métrique connue
 * et une plage de dates cohérente est rejeté : mieux vaut « je n'ai pas compris » qu'une
 * grille qui saute au hasard.
 *
 * Volontairement écrit à la main plutôt qu'avec le Structured Output (`@Generable`) : ce
 * dernier est en Alpha et impose le plugin KSP 2.3.6, donc Kotlin 2.3 ; le projet est en
 * Kotlin 2.2. À rebasculer si le projet monte de version.
 */
internal fun parseGridQuery(raw: String, years: List<Int>): GridQuery? {
    val start = raw.indexOf('{')
    val end = raw.lastIndexOf('}')
    if (start < 0 || end <= start) return null

    return runCatching {
        val json = JSONObject(raw.substring(start, end + 1))
        val metric = Metric.entries.firstOrNull { it.name == json.optString("metric").uppercase() }
            ?: return null
        val from = LocalDate.parse(json.optString("from"))
        val to = LocalDate.parse(json.optString("to"))
        if (from.isAfter(to)) return null
        // Une plage qui ne touche aucune année connue ne surlignerait rien.
        if (years.none { it in from.year..to.year }) return null
        GridQuery(metric, from, to)
    }.getOrNull()
}

// ------------------------------------------------- Résumé hebdo mis en cache

/**
 * Le texte du résumé du dimanche, rédigé pendant que l'app est ouverte et relu par
 * [ReminderReceiver] au moment de notifier.
 *
 * Ce détour n'est pas une optimisation : AICore refuse l'inférence en arrière-plan, et la
 * notification part d'un BroadcastReceiver. Sans cache, pas de résumé rédigé du tout.
 *
 * Le cache porte la date de fin de la semaine qu'il décrit. Passé [MAX_AGE_DAYS], on
 * retombe sur le texte calculé plutôt que d'annoncer les chiffres d'une autre semaine.
 */
object WeeklyBrief {
    private const val TEXT = "ai_weekly_text"
    private const val COVERS = "ai_weekly_covers"
    private const val MAX_AGE_DAYS = 2L

    fun store(context: Context, text: String, covers: LocalDate = LocalDate.now()) {
        Prefs.of(context).edit()
            .putString(TEXT, text)
            .putString(COVERS, covers.toString())
            .apply()
    }

    fun load(context: Context, today: LocalDate = LocalDate.now()): String? {
        val prefs = Prefs.of(context)
        val text = prefs.getString(TEXT, null)?.takeIf { it.isNotBlank() } ?: return null
        val covers = runCatching { LocalDate.parse(prefs.getString(COVERS, "")) }.getOrNull() ?: return null
        val age = Duration.between(covers.atStartOfDay(), today.atStartOfDay()).toDays()
        return text.takeIf { age in 0..MAX_AGE_DAYS }
    }

    fun clear(context: Context) {
        Prefs.of(context).edit().remove(TEXT).remove(COVERS).apply()
    }

    /** Vrai si le cache mérite d'être régénéré maintenant que l'app est au premier plan. */
    fun isStale(context: Context, today: LocalDate = LocalDate.now()): Boolean =
        load(context, today) == null
}
