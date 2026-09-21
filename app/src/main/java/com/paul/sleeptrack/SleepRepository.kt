package com.paul.sleeptrack

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.Period
import java.time.ZoneId
import kotlin.random.Random
import kotlin.reflect.KClass

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

/** Lit toutes les métriques autorisées entre deux dates incluses. */
suspend fun loadHealthData(
    client: HealthConnectClient,
    granted: Set<String>,
    from: LocalDate,
    to: LocalDate,
    zone: ZoneId = ZoneId.systemDefault(),
): HealthData = HealthData(
    nights = if (PERMISSION_READ_SLEEP in granted) readSleepByNight(client, from, to, zone) else emptyMap(),
    steps = if (PERMISSION_READ_STEPS in granted) readStepsByDay(client, from, to, zone) else emptyMap(),
    heart = if (PERMISSION_READ_HEART in granted) readRestingHeartRate(client, from, to, zone) else emptyMap(),
    weight = if (PERMISSION_READ_WEIGHT in granted) readWeight(client, from, to, zone) else emptyMap(),
)

suspend fun loadYear(
    client: HealthConnectClient,
    granted: Set<String>,
    year: Int,
    zone: ZoneId = ZoneId.systemDefault(),
): HealthData = loadHealthData(client, granted, LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31), zone)

/** Durée de sommeil par nuit, la nuit étant rattachée à la date du réveil. */
suspend fun readSleepByNight(
    client: HealthConnectClient,
    from: LocalDate,
    to: LocalDate,
    zone: ZoneId = ZoneId.systemDefault(),
): Map<LocalDate, Duration> {
    val start = from.minusDays(1).atStartOfDay(zone).toInstant()
    val end = to.plusDays(2).atStartOfDay(zone).toInstant()
    val intervalsByDate = mutableMapOf<LocalDate, MutableList<Pair<Instant, Instant>>>()

    var pageToken: String? = null
    do {
        val response = client.readRecords(
            ReadRecordsRequest(
                recordType = SleepSessionRecord::class,
                timeRangeFilter = TimeRangeFilter.between(start, end),
                pageToken = pageToken,
            )
        )
        for (session in response.records) {
            val date = session.endTime.atZone(zone).toLocalDate()
            if (date < from || date > to) continue
            val asleep = if (session.stages.isEmpty()) {
                listOf(session.startTime to session.endTime)
            } else {
                session.stages.filter { it.stage !in NOT_ASLEEP }.map { it.startTime to it.endTime }
            }
            intervalsByDate.getOrPut(date) { mutableListOf() } += asleep
        }
        pageToken = response.pageToken
    } while (pageToken != null)

    return intervalsByDate
        .mapValues { (_, intervals) -> mergedDuration(intervals) }
        .filterValues { !it.isZero }
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
): Map<LocalDate, Long> {
    val out = mutableMapOf<LocalDate, Long>()
    val limit = minOf(to.plusDays(1), LocalDate.now(zone).plusDays(1))
    var chunkStart = from
    while (chunkStart.isBefore(limit)) {
        val chunkEnd = minOf(chunkStart.plusMonths(1), limit)
        val groups = client.aggregateGroupByPeriod(
            AggregateGroupByPeriodRequest(
                metrics = setOf(StepsRecord.COUNT_TOTAL),
                timeRangeFilter = TimeRangeFilter.between(chunkStart.atStartOfDay(), chunkEnd.atStartOfDay()),
                timeRangeSlicer = Period.ofDays(1),
            )
        )
        for (group in groups) {
            val count = group.result[StepsRecord.COUNT_TOTAL] ?: continue
            if (count > 0) out[group.startTime.toLocalDate()] = count
        }
        chunkStart = chunkStart.plusMonths(1)
    }
    return out
}

/** Fréquence cardiaque au repos : moyenne des relevés du jour. */
suspend fun readRestingHeartRate(
    client: HealthConnectClient,
    from: LocalDate,
    to: LocalDate,
    zone: ZoneId = ZoneId.systemDefault(),
): Map<LocalDate, Double> = readDailyMean(
    client, RestingHeartRateRecord::class, from, to, zone,
    at = { it.time }, value = { it.beatsPerMinute.toDouble() },
)

/** Poids en kilos : moyenne des pesées du jour. */
suspend fun readWeight(
    client: HealthConnectClient,
    from: LocalDate,
    to: LocalDate,
    zone: ZoneId = ZoneId.systemDefault(),
): Map<LocalDate, Double> = readDailyMean(
    client, WeightRecord::class, from, to, zone,
    at = { it.time }, value = { it.weight.inKilograms },
)

// Cœur au repos et poids tiennent en quelques relevés par jour : la lecture brute
// évite d'avoir à deviner le type de retour des agrégats.
private suspend fun <T : Record> readDailyMean(
    client: HealthConnectClient,
    type: KClass<T>,
    from: LocalDate,
    to: LocalDate,
    zone: ZoneId,
    at: (T) -> Instant,
    value: (T) -> Double,
): Map<LocalDate, Double> {
    val start = from.atStartOfDay(zone).toInstant()
    val end = to.plusDays(1).atStartOfDay(zone).toInstant()
    val sums = mutableMapOf<LocalDate, Pair<Double, Int>>()

    var pageToken: String? = null
    do {
        val response = client.readRecords(
            ReadRecordsRequest(
                recordType = type,
                timeRangeFilter = TimeRangeFilter.between(start, end),
                pageToken = pageToken,
            )
        )
        for (record in response.records) {
            val date = at(record).atZone(zone).toLocalDate()
            val (sum, n) = sums[date] ?: (0.0 to 0)
            sums[date] = (sum + value(record)) to (n + 1)
        }
        pageToken = response.pageToken
    } while (pageToken != null)

    return sums.mapValues { (_, acc) -> acc.first / acc.second }
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
