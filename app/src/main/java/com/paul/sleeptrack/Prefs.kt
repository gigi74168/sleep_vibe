package com.paul.sleeptrack

import android.content.Context
import android.content.SharedPreferences
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.time.Duration
import java.time.LocalDate

/** Réglages de l'app : rappels, objectif, métriques visibles, métrique du widget. */
object Prefs {
    private const val FILE = "sommeil"

    const val EVENING_ENABLED = "evening_enabled"
    const val EVENING_HOUR = "evening_hour"
    const val WEEKLY_ENABLED = "weekly_enabled"
    const val WEEKLY_HOUR = "weekly_hour"
    const val GOAL_MINUTES = "goal_minutes"
    const val WIDGET_METRIC = "widget_metric"
    const val HIDDEN_METRICS = "hidden_metrics"
    const val LAST_METRIC = "last_metric"
    const val SHOW_STATS = "show_stats"
    const val SHOW_STREAKS = "show_streaks"
    const val SHOW_WEEK = "show_week"
    const val SHOW_LEGEND = "show_legend"
    const val SHOW_CORRELATION = "show_correlation"
    const val SHOW_NOTES = "show_notes"
    const val SHOW_AI = "show_ai"
    const val CELL_SIZE = "cell_size"
    const val LANDSCAPE_BIG = "landscape_big"
    /** Jour (et fuseau) de la dernière lecture du temps d'écran. */
    const val SCREEN_SYNCED = "screen_synced"

    const val DEFAULT_EVENING_HOUR = 22
    const val DEFAULT_WEEKLY_HOUR = 19
    const val DEFAULT_GOAL_MINUTES = 420

    /** Côté d'une case, en dp. 0 = la grille s'ajuste à la largeur de l'écran. */
    val CELL_SIZES = listOf(0, 13, 18, 24)
    val CELL_LABELS = listOf("Ajustée", "Moyenne", "Grande", "Très grande")

    fun of(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun eveningEnabled(context: Context) = of(context).getBoolean(EVENING_ENABLED, false)
    fun eveningHour(context: Context) = of(context).getInt(EVENING_HOUR, DEFAULT_EVENING_HOUR)
    fun weeklyEnabled(context: Context) = of(context).getBoolean(WEEKLY_ENABLED, false)
    fun weeklyHour(context: Context) = of(context).getInt(WEEKLY_HOUR, DEFAULT_WEEKLY_HOUR)
    fun goalMinutes(context: Context) = of(context).getInt(GOAL_MINUTES, DEFAULT_GOAL_MINUTES)
    fun goal(context: Context): Duration = Duration.ofMinutes(goalMinutes(context).toLong())

    /** Ce que l'écran principal montre autour de la grille. */
    fun display(context: Context): DisplayPrefs = of(context).let {
        DisplayPrefs(
            stats = it.getBoolean(SHOW_STATS, true),
            streaks = it.getBoolean(SHOW_STREAKS, true),
            week = it.getBoolean(SHOW_WEEK, true),
            legend = it.getBoolean(SHOW_LEGEND, true),
            correlation = it.getBoolean(SHOW_CORRELATION, true),
            notes = it.getBoolean(SHOW_NOTES, true),
            ai = it.getBoolean(SHOW_AI, true),
            cellSize = it.getInt(CELL_SIZE, 0),
            landscapeBig = it.getBoolean(LANDSCAPE_BIG, true),
            goalMinutes = it.getInt(GOAL_MINUTES, DEFAULT_GOAL_MINUTES),
        )
    }

    fun setFlag(context: Context, key: String, value: Boolean) {
        of(context).edit().putBoolean(key, value).apply()
    }

    fun setCellSize(context: Context, dp: Int) {
        of(context).edit().putInt(CELL_SIZE, dp).apply()
    }

    /** Les métriques montrées dans l'app. Le sommeil ne se masque pas : c'est le sujet. */
    fun visibleMetrics(context: Context): List<Metric> {
        val hidden = of(context).getStringSet(HIDDEN_METRICS, emptySet()).orEmpty()
        return Metric.entries.filter { !it.canHide || it.name !in hidden }
    }

    fun isVisible(context: Context, metric: Metric) = metric in visibleMetrics(context)

    fun setVisible(context: Context, metric: Metric, visible: Boolean) {
        if (!metric.canHide) return
        val hidden = of(context).getStringSet(HIDDEN_METRICS, emptySet()).orEmpty().toMutableSet()
        if (visible) hidden -= metric.name else hidden += metric.name
        of(context).edit().putStringSet(HIDDEN_METRICS, hidden).apply()
        // Une métrique masquée ne peut plus être ni l'onglet ouvert, ni celle du widget.
        if (!visible) {
            if (widgetMetric(context) == metric) setWidgetMetric(context, Metric.SLEEP)
            if (lastMetric(context) == metric) setLastMetric(context, Metric.SLEEP)
        }
    }

    /** Onglet rouvert au prochain lancement. */
    fun lastMetric(context: Context): Metric = storedMetric(context, LAST_METRIC)

    fun setLastMetric(context: Context, metric: Metric) {
        of(context).edit().putString(LAST_METRIC, metric.name).apply()
    }

    /** Métrique dessinée par le widget, choisie dans les réglages. */
    fun widgetMetric(context: Context): Metric = storedMetric(context, WIDGET_METRIC)

    fun setWidgetMetric(context: Context, metric: Metric) {
        of(context).edit().putString(WIDGET_METRIC, metric.name).apply()
    }

    private fun storedMetric(context: Context, key: String): Metric {
        val stored = runCatching { Metric.valueOf(of(context).getString(key, null) ?: "") }
            .getOrDefault(Metric.SLEEP)
        return if (isVisible(context, stored)) stored else Metric.SLEEP
    }
}

/** Ce que l'écran principal affiche sous la grille, et la taille des cases. */
data class DisplayPrefs(
    val stats: Boolean = true,
    val streaks: Boolean = true,
    val week: Boolean = true,
    val legend: Boolean = true,
    val correlation: Boolean = true,
    val notes: Boolean = true,
    /** Commentaires rédigés par Gemini Nano, là où l'appareil sait les produire. */
    val ai: Boolean = true,
    val cellSize: Int = 0,
    val landscapeBig: Boolean = true,
    /** Repris ici parce que les séries s'en servent comme seuil. */
    val goalMinutes: Int = Prefs.DEFAULT_GOAL_MINUTES,
)

/**
 * Dernières données lues, gardées pour que le widget et les rappels aient quelque chose
 * à afficher sans rouvrir l'app. Rien ne sort du téléphone : c'est un simple fichier privé.
 *
 * Il vivait dans les préférences, que chaque démarrage de processus (widget compris) relit
 * en entier et que chaque modification réécrit en entier. Il a maintenant son propre
 * fichier, gardé en mémoire, et réécrit seulement quand son contenu change.
 */
object DataCache {
    private const val FILE = "cache.json"
    /** Ancien emplacement, dans les préférences : relu une fois, effacé à l'écriture suivante. */
    private const val LEGACY_KEY = "cache"
    private const val DAYS_KEPT = 200L

    private val lock = Any()
    /** Contenu du fichier, pour savoir sans le relire si une sauvegarde change quelque chose. */
    private var stored: String? = null
    private var decoded: HealthData? = null

    private fun file(context: Context) = AtomicFile(File(context.applicationContext.filesDir, FILE))

    /** Garde les derniers jours. Renvoie false quand le cache avait déjà ce contenu. */
    fun save(context: Context, data: HealthData): Boolean = synchronized(lock) {
        val floor = LocalDate.now().minusDays(DAYS_KEPT)
        val root = JSONObject()
        root.put("sleep", jsonOf(data.nights.filterKeys { it > floor }.mapValues { it.value.toMinutes().toDouble() }))
        root.put("steps", jsonOf(data.steps.filterKeys { it > floor }.mapValues { it.value.toDouble() }))
        root.put("heart", jsonOf(data.heart.filterKeys { it > floor }))
        root.put("weight", jsonOf(data.weight.filterKeys { it > floor }))
        root.put("screen", jsonOf(data.screen.filterKeys { it > floor }))
        val json = root.toString()
        if (json == raw(context)) return false

        val f = file(context)
        runCatching {
            val out = f.startWrite()
            try {
                out.write(json.toByteArray(Charsets.UTF_8))
                f.finishWrite(out)
            } catch (e: Exception) {
                f.failWrite(out)
                throw e
            }
        }
        // Comme les préférences avant lui : la mémoire suit même si le disque a refusé.
        stored = json
        decoded = null
        Prefs.of(context).let { if (it.contains(LEGACY_KEY)) it.edit().remove(LEGACY_KEY).apply() }
        true
    }

    fun load(context: Context): HealthData = synchronized(lock) {
        decoded?.let { return it }
        val text = raw(context) ?: return HealthData()
        runCatching {
            val root = JSONObject(text)
            HealthData(
                nights = readMap(root, "sleep").mapValues { Duration.ofMinutes(it.value.toLong()) },
                steps = readMap(root, "steps").mapValues { it.value.toLong() },
                heart = readMap(root, "heart"),
                weight = readMap(root, "weight"),
                screen = readMap(root, "screen"),
            )
        }.getOrDefault(HealthData()).also { decoded = it }
    }

    private fun raw(context: Context): String? = stored ?: (
        runCatching { file(context).readFully().toString(Charsets.UTF_8) }.getOrNull()
            ?: Prefs.of(context).getString(LEGACY_KEY, null)
        ).also { stored = it }

    // Dates triées : le même contenu donne toujours le même texte, ce qui rend la
    // comparaison de save() fiable d'un processus à l'autre.
    private fun jsonOf(values: Map<LocalDate, Double>): JSONObject {
        val obj = JSONObject()
        values.toSortedMap().forEach { (date, value) -> obj.put(date.toString(), value) }
        return obj
    }

    private fun readMap(root: JSONObject, name: String): Map<LocalDate, Double> {
        val obj = root.optJSONObject(name) ?: return emptyMap()
        val out = mutableMapOf<LocalDate, Double>()
        obj.keys().forEach { key ->
            runCatching { out[LocalDate.parse(key)] = obj.getDouble(key) }
        }
        return out
    }
}
