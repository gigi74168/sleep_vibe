package com.paul.sleeptrack

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Duration
import java.time.LocalDate

/**
 * Sauvegarde en JSON : un format lisible à l'œil nu, une ligne par jour, pensé pour être
 * relu par une autre appli ou un tableur autant que par Sleep Track.
 *
 * ```json
 * { "app": "sommeil", "format": 1, "exportedAt": "2026-09-18",
 *   "days": [ { "date": "2026-01-01", "sleepMinutes": 431, "steps": 8123,
 *               "restingHeartRate": 56.2, "weightKg": 72.4, "hrvRmssd": 48.1 } ] }
 * ```
 *
 * La lecture est volontairement tolérante : `days` peut aussi être un objet indexé par date,
 * les heures de sommeil sont acceptées à la place des minutes, et les noms de champs les plus
 * courants ailleurs (`heartRate`, `weight`, `step_count`…) sont reconnus.
 */
const val BACKUP_FORMAT = 1

/**
 * L'historique gardé par l'app : ce qu'elle a déjà lu dans Health Connect, plus ce qui a été
 * importé. Health Connect oublie au-delà de sa propre rétention et ne suit pas d'un téléphone
 * à l'autre ; ce fichier, lui, s'exporte. Il ne quitte le téléphone que si on l'exporte.
 */
object Archive {
    private const val FILE = "archive.json"

    /**
     * Copie en mémoire du fichier, relu une fois par processus et non plus à chaque
     * ouverture. Le verrou sérialise lectures et écritures : deux fusions simultanées
     * pouvaient lire un fichier à moitié écrit, le prendre pour vide et écraser l'historique.
     */
    private val lock = Any()
    private var cached: HealthData? = null

    // Écrit à côté puis renomme : une lecture ne tombe jamais sur un fichier à moitié
    // écrit, et un arrêt en pleine écriture laisse la version précédente intacte.
    private fun file(context: Context) = AtomicFile(File(context.applicationContext.filesDir, FILE))

    fun load(context: Context): HealthData = synchronized(lock) {
        cached ?: read(context).also { cached = it }
    }

    // Fichier absent (premier lancement) ou illisible : historique vide, comme avant.
    private fun read(context: Context): HealthData = runCatching {
        decodeBackup(JSONObject(file(context).readFully().toString(Charsets.UTF_8)))
    }.getOrDefault(HealthData())

    fun save(context: Context, data: HealthData): Unit = synchronized(lock) {
        val f = file(context)
        val written = runCatching {
            val out = f.startWrite()
            try {
                out.write(encodeBackup(data).toString().toByteArray(Charsets.UTF_8))
                f.finishWrite(out)
            } catch (e: Exception) {
                f.failWrite(out)
                throw e
            }
        }.isSuccess
        // Si l'écriture a échoué, la prochaine lecture repart du fichier, comme avant.
        cached = if (written) data else null
    }

    /** Ajoute sans rien perdre ; en cas de doublon, la valeur entrante gagne. */
    fun merge(context: Context, incoming: HealthData): HealthData = synchronized(lock) {
        val current = load(context)
        if (incoming.isEmpty()) return current
        val merged = current + incoming
        // Rien de neuf, le cas de presque chaque ouverture : pas de réécriture.
        if (merged != current) save(context, merged)
        merged
    }

    fun clear(context: Context): Unit = synchronized(lock) {
        runCatching { file(context).delete() }
        cached = null
        forgetScreenTimeSync(context)
    }
}

/** Écrit la sauvegarde dans le fichier choisi par l'utilisateur. Renvoie le nombre de jours. */
fun exportBackup(context: Context, uri: Uri, data: HealthData): Int {
    val json = encodeBackup(data).toString(2)
    context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
        out.write(json.toByteArray(Charsets.UTF_8))
    } ?: error("fichier inaccessible en écriture")
    return dayCount(data)
}

/**
 * Même contenu en CSV, pour les tableurs. Séparateur virgule et point décimal : la convention
 * qu'attendent les tableurs à l'import, quelle que soit la langue de l'interface.
 */
fun exportCsv(context: Context, uri: Uri, data: HealthData): Int {
    val days = allDays(data).sorted()
    val text = buildString {
        append("date,sleep_minutes,steps,resting_heart_rate,weight_kg,screen_minutes,hrv_rmssd_ms\n")
        for (date in days) {
            append(date).append(',')
            append(data.nights[date]?.toMinutes() ?: "").append(',')
            append(data.steps[date] ?: "").append(',')
            append(data.heart[date]?.let { round1(it) } ?: "").append(',')
            append(data.weight[date]?.let { round1(it) } ?: "").append(',')
            append(data.screen[date]?.let { Math.round(it) } ?: "").append(',')
            append(data.hrv[date]?.let { round1(it) } ?: "").append('\n')
        }
    }
    context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
        out.write(text.toByteArray(Charsets.UTF_8))
    } ?: error("fichier inaccessible en écriture")
    return days.size
}

/** Relit un fichier de sauvegarde. Lève une exception si le JSON est illisible. */
fun importBackup(context: Context, uri: Uri): HealthData {
    val text = context.contentResolver.openInputStream(uri)
        ?.use { it.readBytes().toString(Charsets.UTF_8) }
        ?: error("fichier illisible")
    return decodeBackup(JSONObject(text))
}

fun dayCount(data: HealthData): Int = allDays(data).size

private fun allDays(data: HealthData): Set<LocalDate> =
    data.nights.keys + data.steps.keys + data.heart.keys + data.weight.keys + data.screen.keys + data.hrv.keys

// Le score de récupération n'est pas sauvegardé : il se recalcule à partir de ces séries.
fun encodeBackup(data: HealthData): JSONObject {
    val days = allDays(data).sorted()
    val array = JSONArray()
    for (date in days) {
        val day = JSONObject().put("date", date.toString())
        data.nights[date]?.let { day.put("sleepMinutes", it.toMinutes()) }
        data.steps[date]?.let { day.put("steps", it) }
        data.heart[date]?.let { day.put("restingHeartRate", round1(it)) }
        data.weight[date]?.let { day.put("weightKg", round1(it)) }
        data.screen[date]?.let { day.put("screenMinutes", Math.round(it)) }
        data.hrv[date]?.let { day.put("hrvRmssd", round1(it)) }
        array.put(day)
    }
    val units = JSONObject()
        .put("sleepMinutes", "minutes de sommeil dans la nuit qui se termine ce jour-là")
        .put("steps", "pas du jour")
        .put("restingHeartRate", "battements par minute au repos")
        .put("weightKg", "kilogrammes")
        .put("screenMinutes", "minutes d'écran allumé et déverrouillé")
        .put("hrvRmssd", "variabilité cardiaque nocturne (RMSSD), en millisecondes")
    return JSONObject()
        .put("app", "sommeil")
        .put("format", BACKUP_FORMAT)
        .put("exportedAt", LocalDate.now().toString())
        .put("units", units)
        .put("days", array)
}

fun decodeBackup(root: JSONObject): HealthData {
    val nights = mutableMapOf<LocalDate, Duration>()
    val steps = mutableMapOf<LocalDate, Long>()
    val heart = mutableMapOf<LocalDate, Double>()
    val weight = mutableMapOf<LocalDate, Double>()
    val screen = mutableMapOf<LocalDate, Double>()
    val hrv = mutableMapOf<LocalDate, Double>()

    fun readDay(date: LocalDate, day: JSONObject) {
        number(day, "sleepMinutes", "sleep_minutes", "minutesAsleep", "sleep")
            ?.let { nights[date] = Duration.ofMinutes(it.toLong()) }
        number(day, "sleepHours", "hoursAsleep")
            ?.let { nights[date] = Duration.ofMinutes((it * 60).toLong()) }
        number(day, "steps", "step_count", "stepCount")?.let { steps[date] = it.toLong() }
        number(day, "restingHeartRate", "resting_heart_rate", "heartRate", "bpm")
            ?.let { heart[date] = it }
        number(day, "weightKg", "weight_kg", "weight")?.let { weight[date] = it }
        number(day, "screenMinutes", "screen_minutes", "screenTimeMinutes", "screenTime")
            ?.let { screen[date] = it }
        number(day, "hrvRmssd", "hrv_rmssd_ms", "hrv", "rmssd")?.let { hrv[date] = it }
    }

    when (val days = root.opt("days")) {
        is JSONArray -> for (i in 0 until days.length()) {
            val day = days.optJSONObject(i) ?: continue
            val date = parseDate(day.optString("date").ifBlank { day.optString("day") }) ?: continue
            readDay(date, day)
        }
        // Forme « indexée par date », plus compacte et courante dans d'autres exports.
        is JSONObject -> days.keys().forEach { key ->
            val date = parseDate(key) ?: return@forEach
            days.optJSONObject(key)?.let { readDay(date, it) }
        }
    }

    // Dernière tolérance : des séries à plat, une par métrique.
    flatSeries(root, "sleepMinutes", "sleep")?.forEach { (d, v) -> nights[d] = Duration.ofMinutes(v.toLong()) }
    flatSeries(root, "steps")?.forEach { (d, v) -> steps[d] = v.toLong() }
    flatSeries(root, "restingHeartRate", "heart")?.forEach { (d, v) -> heart[d] = v }
    flatSeries(root, "weightKg", "weight")?.forEach { (d, v) -> weight[d] = v }
    flatSeries(root, "screenMinutes", "screen")?.forEach { (d, v) -> screen[d] = v }
    flatSeries(root, "hrvRmssd", "hrv")?.forEach { (d, v) -> hrv[d] = v }

    return HealthData(nights, steps, heart, weight, screen, hrv)
}

private fun flatSeries(root: JSONObject, vararg names: String): Map<LocalDate, Double>? {
    val obj = names.firstNotNullOfOrNull { root.optJSONObject(it) } ?: return null
    val out = mutableMapOf<LocalDate, Double>()
    obj.keys().forEach { key ->
        val date = parseDate(key) ?: return@forEach
        val value = obj.optDouble(key, Double.NaN)
        if (!value.isNaN()) out[date] = value
    }
    return out
}

private fun number(day: JSONObject, vararg names: String): Double? {
    for (name in names) {
        if (!day.has(name) || day.isNull(name)) continue
        val value = day.optDouble(name, Double.NaN)
        if (!value.isNaN()) return value
    }
    return null
}

// « 2026-01-01 » comme « 2026-01-01T23:12:00Z » : seule la date nous intéresse.
private fun parseDate(raw: String?): LocalDate? {
    val text = raw?.trim().orEmpty()
    if (text.length < 10) return null
    return runCatching { LocalDate.parse(text.take(10)) }.getOrNull()
}

private fun round1(value: Double): Double = Math.round(value * 10.0) / 10.0
