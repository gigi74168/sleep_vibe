package com.paul.sleeptrack

import android.content.Context
import android.content.SharedPreferences
import android.util.AtomicFile
import com.paul.sleeptrack.ui.theme.ThemeChoice
import com.paul.sleeptrack.ui.theme.ThemeMode
import org.json.JSONObject
import java.io.File
import java.time.Duration
import java.time.LocalDate

/** Réglages de l'app : rappels, objectifs, récupération, accueil, métriques visibles, widget. */
object Prefs {
    private const val FILE = "sommeil"

    const val EVENING_ENABLED = "evening_enabled"
    const val EVENING_HOUR = "evening_hour"
    const val WEEKLY_ENABLED = "weekly_enabled"
    const val WEEKLY_HOUR = "weekly_hour"
    const val GOAL_MINUTES = "goal_minutes"
    const val GOAL_STEPS = "goal_steps"
    const val GOAL_SCREEN = "goal_screen"
    const val GOAL_RECOVERY = "goal_recovery"
    const val WIDGET_METRIC = "widget_metric"
    const val HIDDEN_METRICS = "hidden_metrics"
    const val LAST_METRIC = "last_metric"
    const val SHOW_STATS = "show_stats"
    const val SHOW_STREAKS = "show_streaks"
    const val SHOW_WEEK = "show_week"
    const val SHOW_LEGEND = "show_legend"
    const val SHOW_CORRELATION = "show_correlation"
    const val SHOW_NOTES = "show_notes"
    const val SHOW_DAY_DETAIL = "show_day_detail"
    const val SHOW_SHARE = "show_share"
    const val CELL_SIZE = "cell_size"
    const val LANDSCAPE_BIG = "landscape_big"
    /** Jour (et fuseau) de la dernière lecture du temps d'écran. */
    const val SCREEN_SYNCED = "screen_synced"

    const val REC_SLEEP = "rec_sleep"
    const val REC_HRV = "rec_hrv"
    const val REC_LOAD = "rec_load"
    const val BASELINE_DAYS = "baseline_days"

    const val HOME_CARD = "home_card"
    const val HOME_GAUGE = "home_gauge"
    const val HOME_BREAKDOWN = "home_breakdown"
    const val HOME_STRIPS = "home_strips"
    const val HOME_STRIP_SIZE = "home_strip_size"
    const val HOME_TAP_DETAIL = "home_tap_detail"
    const val START_TAB = "start_tab"

    const val THEME = "theme"
    const val THEME_MODE = "theme_mode"

    const val DEFAULT_EVENING_HOUR = 22
    const val DEFAULT_WEEKLY_HOUR = 19
    const val DEFAULT_GOAL_MINUTES = Goals.DEFAULT_SLEEP_MINUTES

    /** Côté d'une case, en dp. 0 = la grille s'ajuste à la largeur de l'écran. */
    val CELL_SIZES = listOf(0, 13, 18, 24)
    val CELL_LABELS = listOf("Ajustée", "Moyenne", "Grande", "Très grande")

    /** Hauteur d'une bande de l'accueil, en dp. */
    val STRIP_SIZES = listOf(100, 140, 180)
    val STRIP_SIZE_LABELS = listOf("Compacte", "Normale", "Grande")

    private val DEFAULT_HOME_STRIPS = setOf(Metric.SLEEP, Metric.STEPS, Metric.RECOVERY)

    fun of(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Thème et mode choisis ; Aube et le mode du système par défaut. */
    fun appearance(context: Context): Appearance = of(context).let {
        Appearance(ThemeChoice.of(it.getString(THEME, null)), ThemeMode.of(it.getString(THEME_MODE, null)))
    }

    fun setTheme(context: Context, theme: ThemeChoice) {
        of(context).edit().putString(THEME, theme.key).apply()
    }

    fun setThemeMode(context: Context, mode: ThemeMode) {
        of(context).edit().putString(THEME_MODE, mode.key).apply()
    }

    /** L'apparence telle qu'elle part dans l'export JSON. */
    fun appearanceJson(context: Context): JSONObject = appearance(context).let {
        JSONObject().put("theme", it.theme.key).put("mode", it.mode.key)
    }

    /** Reprend l'apparence d'un export ; une valeur inconnue ou absente ne change rien. */
    fun importAppearance(context: Context, settings: JSONObject) {
        val editor = of(context).edit()
        settings.optString("theme").takeIf { v -> ThemeChoice.entries.any { it.key == v } }
            ?.let { editor.putString(THEME, it) }
        settings.optString("mode").takeIf { v -> ThemeMode.entries.any { it.key == v } }
            ?.let { editor.putString(THEME_MODE, it) }
        editor.apply()
    }

    fun eveningEnabled(context: Context) = of(context).getBoolean(EVENING_ENABLED, false)
    fun eveningHour(context: Context) = of(context).getInt(EVENING_HOUR, DEFAULT_EVENING_HOUR)
    fun weeklyEnabled(context: Context) = of(context).getBoolean(WEEKLY_ENABLED, false)
    fun weeklyHour(context: Context) = of(context).getInt(WEEKLY_HOUR, DEFAULT_WEEKLY_HOUR)

    /** Objectif de sommeil comme durée, pour les rappels. */
    fun goal(context: Context): Duration = Duration.ofMinutes(goals(context).sleepMinutes.toLong())

    /** Les quatre objectifs réglables : ils pilotent aussi les couleurs des grilles. */
    fun goals(context: Context): Goals = of(context).let {
        Goals(
            sleepMinutes = it.getInt(GOAL_MINUTES, Goals.DEFAULT_SLEEP_MINUTES),
            steps = it.getInt(GOAL_STEPS, Goals.DEFAULT_STEPS),
            screenMinutes = it.getInt(GOAL_SCREEN, Goals.DEFAULT_SCREEN_MINUTES),
            recoveryGood = it.getInt(GOAL_RECOVERY, Goals.DEFAULT_RECOVERY_GOOD),
        )
    }

    fun setGoal(context: Context, key: String, value: Int) {
        of(context).edit().putInt(key, value).apply()
    }

    /** Ce qui entre dans le score de récupération, et sa période de référence. */
    fun recoveryConfig(context: Context): RecoveryConfig {
        val p = of(context)
        val components = buildSet {
            if (p.getBoolean(REC_SLEEP, true)) add(RecoveryComponent.SLEEP)
            if (p.getBoolean(REC_HRV, true)) add(RecoveryComponent.HRV)
            if (p.getBoolean(REC_LOAD, true)) add(RecoveryComponent.LOAD)
        }
        return RecoveryConfig(
            components = components,
            baselineDays = p.getInt(BASELINE_DAYS, DEFAULT_BASELINE_DAYS),
            sleepGoalMinutes = goals(context).sleepMinutes,
        )
    }

    fun setRecoveryComponent(context: Context, component: RecoveryComponent, enabled: Boolean) {
        val key = when (component) {
            RecoveryComponent.SLEEP -> REC_SLEEP
            RecoveryComponent.HRV -> REC_HRV
            RecoveryComponent.LOAD -> REC_LOAD
        }
        of(context).edit().putBoolean(key, enabled).apply()
    }

    fun setBaselineDays(context: Context, days: Int) {
        of(context).edit().putInt(BASELINE_DAYS, days).apply()
    }

    /** Ce que l'accueil montre : carte de récupération, bandes récentes, toucher des cases. */
    fun home(context: Context): HomePrefs = of(context).let {
        HomePrefs(
            card = it.getBoolean(HOME_CARD, true),
            gauge = it.getBoolean(HOME_GAUGE, true),
            breakdown = it.getBoolean(HOME_BREAKDOWN, true),
            strips = it.getStringSet(HOME_STRIPS, null)
                ?.mapNotNull { name -> runCatching { Metric.valueOf(name) }.getOrNull() }
                ?.toSet()
                ?: DEFAULT_HOME_STRIPS,
            stripSize = it.getInt(HOME_STRIP_SIZE, 1).coerceIn(0, STRIP_SIZES.lastIndex),
            tapDetail = it.getBoolean(HOME_TAP_DETAIL, true),
            startTab = it.getString(START_TAB, "HOME") ?: "HOME",
        )
    }

    fun setHomeFlag(context: Context, key: String, value: Boolean) {
        of(context).edit().putBoolean(key, value).apply()
    }

    fun setHomeStrips(context: Context, strips: Set<Metric>) {
        of(context).edit().putStringSet(HOME_STRIPS, strips.map { it.name }.toSet()).apply()
    }

    fun setHomeStripSize(context: Context, index: Int) {
        of(context).edit().putInt(HOME_STRIP_SIZE, index.coerceIn(0, STRIP_SIZES.lastIndex)).apply()
    }

    fun setStartTab(context: Context, tab: String) {
        of(context).edit().putString(START_TAB, tab).apply()
    }

    /** Ce que l'écran principal montre autour de la grille. */
    fun display(context: Context): DisplayPrefs = of(context).let {
        DisplayPrefs(
            stats = it.getBoolean(SHOW_STATS, true),
            streaks = it.getBoolean(SHOW_STREAKS, true),
            week = it.getBoolean(SHOW_WEEK, true),
            legend = it.getBoolean(SHOW_LEGEND, true),
            correlation = it.getBoolean(SHOW_CORRELATION, true),
            notes = it.getBoolean(SHOW_NOTES, true),
            cellSize = it.getInt(CELL_SIZE, 0),
            landscapeBig = it.getBoolean(LANDSCAPE_BIG, true),
            dayDetail = it.getBoolean(SHOW_DAY_DETAIL, true),
            showShare = it.getBoolean(SHOW_SHARE, true),
        )
    }

    fun setFlag(context: Context, key: String, value: Boolean) {
        of(context).edit().putBoolean(key, value).apply()
    }

    fun setCellSize(context: Context, dp: Int) {
        of(context).edit().putInt(CELL_SIZE, dp).apply()
    }

    /** Les métriques montrées dans l'app. Il en reste toujours au moins une. */
    fun visibleMetrics(context: Context): List<Metric> {
        val hidden = of(context).getStringSet(HIDDEN_METRICS, emptySet()).orEmpty()
        val visible = Metric.entries.filter { it.name !in hidden }
        return visible.ifEmpty { listOf(Metric.entries.first()) }
    }

    fun isVisible(context: Context, metric: Metric) = metric in visibleMetrics(context)

    fun setVisible(context: Context, metric: Metric, visible: Boolean) {
        val current = visibleMetrics(context)
        // Le dernier interrupteur allumé ne peut pas s'éteindre : il faut au moins une métrique.
        if (!visible && current.size <= 1 && metric in current) return
        val hidden = of(context).getStringSet(HIDDEN_METRICS, emptySet()).orEmpty().toMutableSet()
        if (visible) hidden -= metric.name else hidden += metric.name
        of(context).edit().putStringSet(HIDDEN_METRICS, hidden).apply()
        // Une métrique masquée ne peut plus être ni l'onglet ouvert, ni celle du widget.
        if (!visible) {
            val fallback = visibleMetrics(context).first()
            if (widgetMetric(context) == metric) setWidgetMetric(context, fallback)
            if (lastMetric(context) == metric) setLastMetric(context, fallback)
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
        val visible = visibleMetrics(context)
        val stored = runCatching { Metric.valueOf(of(context).getString(key, null) ?: "") }.getOrNull()
        return if (stored != null && stored in visible) stored else visible.first()
    }

    /** Efface tous les réglages, sauf la synchronisation du temps d'écran. Ne touche aux données. */
    fun resetSettings(context: Context) {
        val prefs = of(context)
        val synced = prefs.getString(SCREEN_SYNCED, null)
        val editor = prefs.edit().clear()
        if (synced != null) editor.putString(SCREEN_SYNCED, synced)
        editor.apply()
        Reminders.reschedule(context)
    }
}

data class Appearance(val theme: ThemeChoice = ThemeChoice.AUBE, val mode: ThemeMode = ThemeMode.SYSTEM)

/** Ce que l'écran principal affiche sous la grille, et la taille des cases. */
data class DisplayPrefs(
    val stats: Boolean = true,
    val streaks: Boolean = true,
    val week: Boolean = true,
    val legend: Boolean = true,
    val correlation: Boolean = true,
    val notes: Boolean = true,
    val cellSize: Int = 0,
    val landscapeBig: Boolean = true,
    val dayDetail: Boolean = true,
    val showShare: Boolean = true,
)

/** Ce que l'accueil montre : carte de récupération, bandes récentes, toucher des cases. */
data class HomePrefs(
    val card: Boolean = true,
    val gauge: Boolean = true,
    val breakdown: Boolean = true,
    val strips: Set<Metric> = setOf(Metric.SLEEP, Metric.STEPS, Metric.RECOVERY),
    val stripSize: Int = 1,
    val tapDetail: Boolean = true,
    val startTab: String = "HOME",
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
        root.put("screen", jsonOf(data.screen.filterKeys { it > floor }))
        root.put("hrv", jsonOf(data.hrv.filterKeys { it > floor }))
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
                screen = readMap(root, "screen"),
                hrv = readMap(root, "hrv"),
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
