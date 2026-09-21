package com.paul.sleeptrack

import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.aggregate.AggregateMetric
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.health.connect.client.units.Mass
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.Period
import java.time.ZoneId
import kotlin.random.Random

const val PERMISSION_READ_HISTORY = "android.permission.health.READ_HEALTH_DATA_HISTORY"
const val PERMISSION_READ_BACKGROUND = "android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND"
val PERMISSION_READ_SLEEP = HealthPermission.getReadPermission(SleepSessionRecord::class)
val PERMISSION_READ_STEPS = HealthPermission.getReadPermission(StepsRecord::class)
val PERMISSION_READ_HEART = HealthPermission.getReadPermission(RestingHeartRateRecord::class)
val PERMISSION_READ_WEIGHT = HealthPermission.getReadPermission(WeightRecord::class)

/** Les lectures de données : au moins une suffit pour afficher quelque chose. */
val DATA_PERMISSIONS = mapOf(
    Metric.SLEEP to PERMISSION_READ_SLEEP,
    Metric.STEPS to PERMISSION_READ_STEPS,
    Metric.HEART to PERMISSION_READ_HEART,
    Metric.WEIGHT to PERMISSION_READ_WEIGHT,
)

val REQUESTED_PERMISSIONS =
    DATA_PERMISSIONS.values.toSet() + PERMISSION_READ_HISTORY + PERMISSION_READ_BACKGROUND

/** Ce qu'on demande vraiment : inutile de réclamer le poids si l'utilisateur l'a masqué. */
fun requestedPermissions(visible: List<Metric>): Set<String> =
    visible.mapNotNull { DATA_PERMISSIONS[it] }.toSet() + PERMISSION_READ_HISTORY + PERMISSION_READ_BACKGROUND

private val NOT_ASLEEP = setOf(
    SleepSessionRecord.STAGE_TYPE_AWAKE,
    SleepSessionRecord.STAGE_TYPE_OUT_OF_BED,
    SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED,
)

/** Vrai si le message ressemble à un rate limit Health Connect. La classe exacte de
 *  l'exception varie selon la version/l'appareil (ni HealthConnectException ni
 *  IllegalStateException ne correspondent en pratique — testé), donc on se fie au
 *  texte plutôt qu'au type. */
private fun isRateLimitMessage(e: Throwable): Boolean {
    val message = e.message?.lowercase() ?: ""
    return message.contains("rate limit") ||
        message.contains("quota") ||
        message.contains("maximum number of requests") ||
        message.contains("too many requests")
}

/** Essaie l'appel, et s'il se fait jeter pour rate limit, retente avec une pause
 *  croissante avant d'abandonner. Renvoie null si toutes les tentatives échouent
 *  pour rate limit — à l'appelant de garder ce qu'il a déjà accumulé plutôt que de
 *  tout perdre. Toute autre erreur est relancée telle quelle : ce n'est pas à cette
 *  fonction de la masquer. */
private suspend fun <T> onceMoreOnRateLimit(block: suspend () -> T): T? = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    if (!isRateLimitMessage(e)) throw e
    Log.w(TAG, "Health Connect a limité les requêtes, nouvelle tentative dans 3s", e)
    delay(3_000)
    try {
        block()
    } catch (e2: CancellationException) {
        throw e2
    } catch (e2: Exception) {
        if (!isRateLimitMessage(e2)) throw e2
        Log.w(TAG, "Health Connect a encore limité les requêtes, dernière tentative dans 6s")
        delay(6_000)
        try {
            block()
        } catch (e3: CancellationException) {
            throw e3
        } catch (e3: Exception) {
            if (!isRateLimitMessage(e3)) throw e3
            Log.w(TAG, "Health Connect reste saturé, on garde le partiel déjà lu")
            null
        }
    }
}

private const val TAG = "SleepTrack-HC"

/** Ce que renvoie une lecture : les données obtenues, et si le rate limiter de Health
 *  Connect a forcé à s'arrêter en cours de route (dans ce cas, les données sont
 *  partielles — pas fausses, juste incomplètes). */
data class HealthLoadResult(val data: HealthData, val rateLimited: Boolean)

/** Lit toutes les métriques autorisées entre deux dates incluses. `concurrent` contrôle
 *  la stratégie : en parallèle (rapide) pour les petites lectures fréquentes comme le
 *  rattrapage des derniers jours au resume, ou séquentiel + espacé (plus lent mais
 *  bien plus sûr) pour la grosse lecture initiale d'une année complète, où le volume
 *  de données rend un rate limit beaucoup plus probable. Si Health Connect coupe la
 *  lecture en route, on renvoie ce qui a déjà été récupéré plutôt que de tout perdre. */
suspend fun loadHealthData(
    client: HealthConnectClient,
    granted: Set<String>,
    from: LocalDate,
    to: LocalDate,
    zone: ZoneId = ZoneId.systemDefault(),
    concurrent: Boolean = true,
): HealthLoadResult {
    suspend fun sleep() =
        if (PERMISSION_READ_SLEEP in granted) readSleepByNight(client, from, to, zone) else PartialResult(emptyMap(), false)
    suspend fun steps() =
        if (PERMISSION_READ_STEPS in granted) readStepsByDay(client, from, to, zone) else PartialResult(emptyMap(), false)
    suspend fun heart() =
        if (PERMISSION_READ_HEART in granted) readRestingHeartRate(client, from, to, zone) else PartialResult(emptyMap(), false)
    suspend fun weight() =
        if (PERMISSION_READ_WEIGHT in granted) readWeight(client, from, to, zone) else PartialResult(emptyMap(), false)

    // Petite classe locale juste pour porter les 4 résultats typés hors du bloc
    // concurrent/séquentiel sans passer par une List hétérogène (qui perdrait les
    // types précis et forcerait des casts non vérifiés).
    data class FourResults(
        val nights: PartialResult<Map<LocalDate, Duration>>,
        val steps: PartialResult<Map<LocalDate, Double>>,
        val heart: PartialResult<Map<LocalDate, Double>>,
        val weight: PartialResult<Map<LocalDate, Double>>,
    )

    val results = if (concurrent) {
        coroutineScope {
            val n = async { sleep() }
            val s = async { steps() }
            val h = async { heart() }
            val w = async { weight() }
            FourResults(n.await(), s.await(), h.await(), w.await())
        }
    } else {
        val n = sleep(); delay(200)
        val s = steps(); delay(200)
        val h = heart(); delay(200)
        val w = weight()
        FourResults(n, s, h, w)
    }

    return HealthLoadResult(
        data = HealthData(
            nights = results.nights.data,
            steps = results.steps.data.mapValues { it.value.toLong() },
            heart = results.heart.data,
            weight = results.weight.data,
        ),
        rateLimited = results.nights.rateLimited || results.steps.rateLimited ||
            results.heart.rateLimited || results.weight.rateLimited,
    )
}

/**
 * Lit uniquement les métriques agrégées (pas, cœur au repos, poids) sur toute la
 * plage [from..to] — quelques appels `aggregateGroupByPeriod` par métrique plutôt
 * qu'un balayage brut. Utilisé pour remplir toute une année en trois à quatre appels
 * chacune au premier lancement ; le sommeil, lui, reste découpé en semaines.
 */
suspend fun loadAggregated(
    client: HealthConnectClient,
    granted: Set<String>,
    from: LocalDate,
    to: LocalDate,
    zone: ZoneId = ZoneId.systemDefault(),
): PartialResult<HealthData> {
    val start = System.currentTimeMillis()
    val s = if (PERMISSION_READ_STEPS in granted) {
        readStepsByDay(client, from, to, zone)
    } else {
        PartialResult(emptyMap(), false)
    }
    delay(120)
    val h = if (PERMISSION_READ_HEART in granted) {
        readRestingHeartRate(client, from, to, zone)
    } else {
        PartialResult(emptyMap(), false)
    }
    delay(120)
    val w = if (PERMISSION_READ_WEIGHT in granted) {
        readWeight(client, from, to, zone)
    } else {
        PartialResult(emptyMap(), false)
    }
    Log.i(TAG, "Métriques agrégées $from..$to en ${System.currentTimeMillis() - start} ms " +
        "(pas=${s.data.size}, cœur=${h.data.size}, poids=${w.data.size}, rateLimited=${s.rateLimited || h.rateLimited || w.rateLimited})")
    return PartialResult(
        data = HealthData(
            steps = s.data.mapValues { it.value.toLong() },
            heart = h.data,
            weight = w.data,
        ),
        rateLimited = s.rateLimited || h.rateLimited || w.rateLimited,
    )
}

/** Découpe une année en tranches d'une semaine, la plus récente en premier : on veut
 *  afficher les derniers jours tout de suite et combler le reste de l'année en tâche
 *  de fond, plutôt que de faire attendre l'écran sur toute l'année (ou même tout un
 *  mois) d'un coup. Des tranches plus petites qu'un mois, parce qu'une source comme
 *  Health Sync peut écrire beaucoup d'entrées (resynchronisations, doublons) qui
 *  rendent même une fenêtre d'un mois lourde à parcourir. Bornée à aujourd'hui pour
 *  l'année en cours. */
fun weekChunks(year: Int, zone: ZoneId = ZoneId.systemDefault()): List<Pair<LocalDate, LocalDate>> {
    val yearEnd = minOf(LocalDate.of(year, 12, 31), LocalDate.now(zone))
    val chunks = mutableListOf<Pair<LocalDate, LocalDate>>()
    var weekStart = LocalDate.of(year, 1, 1)
    while (!weekStart.isAfter(yearEnd)) {
        val weekEnd = minOf(weekStart.plusDays(6), yearEnd)
        chunks += weekStart to weekEnd
        weekStart = weekStart.plusDays(7)
    }
    return chunks.reversed()
}

/** Résultat d'une sous-lecture : les données glanées, et si elle a dû s'arrêter en
 *  route à cause du rate limiter. */
data class PartialResult<T>(val data: T, val rateLimited: Boolean)

/** Durée de sommeil par nuit, la nuit étant rattachée à la date du réveil. Sur certains
 *  appareils/fournisseurs, la pagination traverse la table entière et peut fournir des
 *  pages vides en pagaille même quand la fenêtre est vide ou minuscule : on s'arrête
 *  sur deux pages vides consécutives, on plafonne le nombre de pages par lecture, et si
 *  le budget de temps est dépassé on renvoie le partiel déjà glané plutôt que rien. */
suspend fun readSleepByNight(
    client: HealthConnectClient,
    from: LocalDate,
    to: LocalDate,
    zone: ZoneId = ZoneId.systemDefault(),
    maxPages: Int = 250,
    timeoutMs: Long = 45_000,
): PartialResult<Map<LocalDate, Duration>> {
    val runStart = System.currentTimeMillis()
    val start = from.minusDays(1).atStartOfDay(zone).toInstant()
    val end = to.plusDays(2).atStartOfDay(zone).toInstant()
    Log.i(TAG, "Sommeil : début lecture $from..$to")
    val intervalsByDate = mutableMapOf<LocalDate, MutableList<Pair<Instant, Instant>>>()

    var pageToken: String? = null
    var firstPage = true
    var rateLimited = false
    var pages = 0
    var sessionsSeen = 0
    var emptyStreak = 0
    try {
        withTimeout(timeoutMs) {
            do {
                if (!firstPage) delay(150)
                firstPage = false
                pages++
                val pageStart = System.currentTimeMillis()
                val response = onceMoreOnRateLimit {
                    client.readRecords(
                        ReadRecordsRequest(
                            recordType = SleepSessionRecord::class,
                            timeRangeFilter = TimeRangeFilter.between(start, end),
                            pageToken = pageToken,
                        )
                    )
                }
                if (response == null) {
                    rateLimited = true
                    return@withTimeout
                }
                sessionsSeen += response.records.size
                Log.i(TAG, "Sommeil $from..$to : page $pages (${response.records.size} sessions, " +
                    "cumulé $sessionsSeen) en ${System.currentTimeMillis() - pageStart} ms")
                for (session in response.records) {
                    val date = session.endTime.atZone(zone).toLocalDate()
                    if (date < from || date > to) continue
                    // Certains fournisseurs (Health Sync sur Huawei Watch GT4) classent tous les
                    // stades en "éveil" : le filtre tomberait sur zéro et la nuit disparaîtrait.
                    // Dans ce cas, on prend l'empan complet de la session comme sommeil plutôt
                    // que de jeter une nuit qui a bien été enregistrée comme telle.
                    val asleep = when {
                        session.stages.isEmpty() -> listOf(session.startTime to session.endTime)
                        else -> {
                            val filtered = session.stages
                                .filter { it.stage !in NOT_ASLEEP }
                                .map { it.startTime to it.endTime }
                            if (filtered.isEmpty()) {
                                listOf(session.startTime to session.endTime)
                            } else {
                                filtered
                            }
                        }
                    }
                    intervalsByDate.getOrPut(date) { mutableListOf() } += asleep
                }
                pageToken = response.pageToken
                if (response.records.isEmpty()) {
                    emptyStreak++
                    // Deux pages vides d'affilée : le fournisseur balaie dans le vide
                    // en plein milieu de nulle part, inutile de continuer.
                    if (emptyStreak >= 2) return@withTimeout
                } else {
                    emptyStreak = 0
                }
                if (pages >= maxPages) return@withTimeout
            } while (pageToken != null)
        }
    } catch (e: TimeoutCancellationException) {
        // On a dépassé le budget de temps : on garde le partiel plutôt que de tout jeter.
        rateLimited = true
    }

    val result = intervalsByDate
        .mapValues { (_, intervals) -> mergedDuration(intervals) }
        .filterValues { !it.isZero }
    Log.i(TAG, "Sommeil $from..$to : $pages page(s), $sessionsSeen session(s), " +
        "${result.size} nuit(s) en ${System.currentTimeMillis() - runStart} ms " +
        "(rateLimited=$rateLimited)")
    return PartialResult(result, rateLimited)
}

// Plusieurs applis (montre + téléphone) peuvent enregistrer la même nuit : on fusionne les chevauchements.
private fun mergedDuration(intervals: List<Pair<Instant, Instant>>): Duration {
    var total = Duration.ZERO
    var curStart: Instant? = null
    var curEnd: Instant? = null
    for ((s, e) in intervals.sortedBy { it.first }) {
        if (curStart == null || curEnd == null || s > curEnd) {
            if (curStart != null && curEnd != null) total += Duration.between(curStart, curEnd)
            curStart = s
            curEnd = e
        } else if (e > curEnd) {
            curEnd = e
        }
    }
    if (curStart != null && curEnd != null) total += Duration.between(curStart, curEnd)
    return total
}

/** Nombre de pas par jour. L'agrégation Health Connect dédoublonne déjà montre et téléphone. */
suspend fun readStepsByDay(
    client: HealthConnectClient,
    from: LocalDate,
    to: LocalDate,
    zone: ZoneId = ZoneId.systemDefault(),
): PartialResult<Map<LocalDate, Double>> = aggregateDaily(
    client, StepsRecord.COUNT_TOTAL, from, to, zone,
)

/** Fréquence cardiaque au repos : moyenne BPM du jour. */
suspend fun readRestingHeartRate(
    client: HealthConnectClient,
    from: LocalDate,
    to: LocalDate,
    zone: ZoneId = ZoneId.systemDefault(),
): PartialResult<Map<LocalDate, Double>> = aggregateDaily(
    client, RestingHeartRateRecord.BPM_AVG, from, to, zone,
)

/** Poids en kilos : moyenne des pesées du jour. Idéalement on n'aurait que la dernière,
 *  mais les courbes du repo attendent une valeur par jour. */
suspend fun readWeight(
    client: HealthConnectClient,
    from: LocalDate,
    to: LocalDate,
    zone: ZoneId = ZoneId.systemDefault(),
): PartialResult<Map<LocalDate, Double>> = aggregateDaily(
    client, WeightRecord.WEIGHT_AVG, from, to, zone,
)

/** Normalise une valeur d'agrégat à un Double sans présumer de son type boxé : le
 *  fournisseur Health Connect (recevoir via Health Sync peut changer la métrique)
 *  renvoie un Long quand la statistique tombe sur un entier (ex. BPM moyen), un
 *  Double sinon, un Mass pour le poids — un `as Double` naïf fait planter la lecture
 *  avec "java.lang.Long cannot be cast to java.lang.Double". */
private fun toAggregateDouble(value: Any?): Double? = when (value) {
    is Double -> value
    is Long -> value.toDouble()
    is Int -> value.toDouble()
    is Float -> value.toDouble()
    is Mass -> value.inKilograms
    else -> null
}

/** Agrégation quotidienne d'une métrique sur la plage, découpée en sous-périodes d'au
 *  plus `bucketMonths` mois : chaque sous-période tient en un seul appel
 *  `aggregateGroupByPeriod`, donc une année entière ne coûte que quelques requêtes au
 *  lieu du balayage par jour qui saturait le rate limiter de Health Connect. */
private suspend fun aggregateDaily(
    client: HealthConnectClient,
    metric: AggregateMetric<*>,
    from: LocalDate,
    to: LocalDate,
    zone: ZoneId,
    bucketMonths: Long = 3,
): PartialResult<Map<LocalDate, Double>> {
    val result = mutableMapOf<LocalDate, Double>()
    val limit = to.plusDays(1)
    var bucketStart = from
    var first = true
    var rateLimited = false
    while (bucketStart.isBefore(limit)) {
        // Un petit espacement évite de déclencher le rate limiter interne de Health
        // Connect quand cette lecture s'ajoute à celles du sommeil/cœur/poids.
        if (!first) delay(120)
        first = false
        val bucketEnd = minOf(bucketStart.plusMonths(bucketMonths), limit)
        val groups = onceMoreOnRateLimit {
            client.aggregateGroupByPeriod(
                AggregateGroupByPeriodRequest(
                    metrics = setOf(metric),
                    // Obligatoire : en découpe quotidienne, AggregateGroupByPeriodRequest
                    // exige un filtre par LocalDateTime (le découpage se fait dans le
                    // fuseau local de l'appareil) ; un filtre par Instant lève
                    // "Either use TimeRangeFilter with LocalDateTime or
                    // AggregateGroupByDurationRequest".
                    timeRangeFilter = TimeRangeFilter.between(
                        bucketStart.atStartOfDay(),
                        bucketEnd.atStartOfDay(),
                    ),
                    timeRangeSlicer = Period.ofDays(1),
                )
            )
        }
        if (groups == null) {
            rateLimited = true
            break
        }
        for (group in groups) {
            val converted = toAggregateDouble(group.result[metric]) ?: continue
            if (converted != 0.0) result[group.startTime.atZone(zone).toLocalDate()] = converted
        }
        bucketStart = bucketEnd
    }
    return PartialResult(result, rateLimited)
}

fun demoHealthData(year: Int): HealthData {
    val rnd = Random(year)
    val today = LocalDate.now()
    val nights = mutableMapOf<LocalDate, Duration>()
    val steps = mutableMapOf<LocalDate, Long>()
    val heart = mutableMapOf<LocalDate, Double>()
    val weight = mutableMapOf<LocalDate, Double>()
    val screen = mutableMapOf<LocalDate, Double>()
    var kg = 72.0
    var activeYesterday = false

    var d = LocalDate.of(year, 1, 1)
    while (d.year == year && !d.isAfter(today)) {
        val active = rnd.nextFloat() > 0.25f
        val walked = (if (active) 9_500 else 4_500) + rnd.nextInt(-2_500, 2_500)
        steps[d] = walked.toLong().coerceAtLeast(400)
        if (rnd.nextFloat() > 0.08f) {
            // La nuit qui se termine le jour d suit la soirée de d-1 : c'est l'activité
            // de la veille qui l'allonge, de quoi donner à la corrélation quelque chose
            // à montrer en mode démo.
            val bonus = if (activeYesterday) 22 else -18
            val minutes = (7.1 * 60 + bonus + rnd.nextDouble(-1.0, 1.0) * 105).toLong().coerceIn(180, 660)
            nights[d] = Duration.ofMinutes(minutes)
        }
        activeYesterday = active
        if (rnd.nextFloat() > 0.3f) heart[d] = 56.0 + rnd.nextDouble(-5.0, 6.0)
        if (rnd.nextFloat() > 0.5f) {
            kg = (kg + rnd.nextDouble(-0.25, 0.22)).coerceIn(69.0, 76.0)
            weight[d] = kg
        }
        // Plus d'écran le week-end ; aujourd'hui n'a pas de valeur, comme en vrai.
        if (d.isBefore(today)) {
            val weekend = d.dayOfWeek.value >= 6
            screen[d] = ((if (weekend) 250.0 else 200.0) + rnd.nextDouble(-70.0, 90.0)).coerceAtLeast(40.0)
        }
        d = d.plusDays(1)
    }
    return HealthData(nights, steps, heart, weight, screen)
}
