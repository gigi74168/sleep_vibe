package com.paul.sleeptrack

import android.Manifest
import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.Settings
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Temps d'écran, lu dans les statistiques d'utilisation d'Android (pas dans Health Connect,
 * qui n'en a pas). On compte le temps où l'écran est allumé **et** déverrouillé : c'est proche
 * du « temps d'écran » de Bien-être numérique, un peu au-dessus puisque l'écran d'accueil
 * compte aussi.
 *
 * Android ne garde ces événements qu'une dizaine de jours. L'app les verse dans son archive à
 * chaque ouverture : l'historique s'allonge ensuite tout seul, tant qu'elle est ouverte au
 * moins une fois par semaine.
 */

/** Jours relus à chaque ouverture ; au-delà, Android a de toute façon purgé les événements. */
private const val LOOKBACK_DAYS = 14L

/**
 * L'accès « données d'utilisation » est une autorisation spéciale, donnée depuis les réglages.
 * L'API 36 déprécie les deux vérifications sans en proposer de troisième pour notre minSdk.
 */
@Suppress("DEPRECATION")
fun hasUsageAccess(context: Context): Boolean {
    val ops = context.getSystemService(AppOpsManager::class.java) ?: return false
    val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
    } else {
        ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
    }
    return when (mode) {
        AppOpsManager.MODE_ALLOWED -> true
        // Certains constructeurs laissent le mode par défaut et s'en remettent à la permission.
        AppOpsManager.MODE_DEFAULT ->
            context.checkCallingOrSelfPermission(Manifest.permission.PACKAGE_USAGE_STATS) ==
                PackageManager.PERMISSION_GRANTED
        else -> false
    }
}

/** Ouvre l'écran des réglages où l'on accorde l'accès, sur la fiche de l'app si possible. */
fun openUsageAccessSettings(context: Context) {
    val direct = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
        .setData(Uri.parse("package:${context.packageName}"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val list = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    // Tous les téléphones n'acceptent pas la fiche directe : on retombe sur la liste.
    runCatching { context.startActivity(direct) }
        .recoverCatching { context.startActivity(list) }
}

/**
 * Minutes d'écran des derniers jours terminés. Aujourd'hui est exclu : une journée en cours
 * ferait baisser toutes les moyennes et passerait pour une journée sobre dans les séries.
 */
fun readRecentScreenTime(context: Context, zone: ZoneId = ZoneId.systemDefault()): Map<LocalDate, Double> {
    // Les événements écran et verrouillage n'existent qu'à partir d'Android 9.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P || !hasUsageAccess(context)) return emptyMap()
    val usage = context.getSystemService(UsageStatsManager::class.java) ?: return emptyMap()

    val today = LocalDate.now(zone)
    val from = today.minusDays(LOOKBACK_DAYS)
    val start = from.atStartOfDay(zone).toInstant().toEpochMilli()
    val end = today.atStartOfDay(zone).toInstant().toEpochMilli()
    // Un jour de plus en amont, pour savoir si l'écran était déjà allumé à minuit.
    val events = usage.queryEvents(start - 86_400_000L, end) ?: return emptyMap()

    val totals = mutableMapOf<LocalDate, Double>()
    fun credit(from: Long, to: Long) {
        var cursor = maxOf(from, start)
        val stop = minOf(to, end)
        while (cursor < stop) {
            val day = Instant.ofEpochMilli(cursor).atZone(zone).toLocalDate()
            val midnight = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val until = minOf(stop, midnight)
            totals[day] = (totals[day] ?: 0.0) + (until - cursor) / 60_000.0
            cursor = until
        }
    }

    val event = UsageEvents.Event()
    var interactive = false
    var locked = false
    var since = 0L
    var firstEvent = Long.MAX_VALUE
    while (events.hasNextEvent()) {
        events.getNextEvent(event)
        val counting = interactive && !locked
        when (event.eventType) {
            UsageEvents.Event.SCREEN_INTERACTIVE -> interactive = true
            UsageEvents.Event.SCREEN_NON_INTERACTIVE -> interactive = false
            UsageEvents.Event.KEYGUARD_SHOWN -> locked = true
            UsageEvents.Event.KEYGUARD_HIDDEN -> locked = false
            else -> continue
        }
        firstEvent = minOf(firstEvent, event.timeStamp)
        val nowCounting = interactive && !locked
        if (counting && !nowCounting) credit(since, event.timeStamp)
        if (!counting && nowCounting) since = event.timeStamp
    }
    if (interactive && !locked) credit(since, end)
    if (firstEvent == Long.MAX_VALUE) return emptyMap()

    // Le premier jour couvert par Android est le plus souvent tronqué : on ne garde que les
    // jours vus en entier, plutôt qu'un chiffre trop bas qui fausserait la grille.
    val firstFullDay = if (firstEvent <= start) {
        from
    } else {
        Instant.ofEpochMilli(firstEvent).atZone(zone).toLocalDate().plusDays(1)
    }
    return totals.filterKeys { it >= firstFullDay && it < today }
}
