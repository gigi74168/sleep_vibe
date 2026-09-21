package com.paul.sleeptrack

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.health.connect.client.HealthConnectClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

private const val ACTION_EVENING = "com.paul.sleeptrack.EVENING"
private const val ACTION_WEEKLY = "com.paul.sleeptrack.WEEKLY"

/**
 * Deux rappels facultatifs, posés avec AlarmManager et reprogrammés à chaque déclenchement :
 * un rappel du soir quand la moyenne de la semaine passe sous l'objectif, et un résumé
 * hebdomadaire. Les alarmes sont inexactes (à quelques minutes près), ce qui évite de
 * demander l'autorisation « alarmes exactes ».
 */
object Reminders {

    const val CHANNEL_EVENING = "rappel"
    const val CHANNEL_WEEKLY = "hebdo"
    const val NOTIFICATION_EVENING = 1
    const val NOTIFICATION_WEEKLY = 2
    const val NOTIFICATION_SAMPLE = 3

    fun reschedule(context: Context) {
        cancel(context, ACTION_EVENING)
        cancel(context, ACTION_WEEKLY)
        if (Prefs.eveningEnabled(context)) {
            schedule(context, ACTION_EVENING, nextDaily(Prefs.eveningHour(context)))
        }
        if (Prefs.weeklyEnabled(context)) {
            schedule(context, ACTION_WEEKLY, nextSunday(Prefs.weeklyHour(context)))
        }
    }

    fun canNotify(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun schedule(context: Context, action: String, at: LocalDateTime) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        val millis = at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pendingIntent(context, action))
    }

    private fun cancel(context: Context, action: String) {
        context.getSystemService(AlarmManager::class.java)?.cancel(pendingIntent(context, action))
    }

    private fun pendingIntent(context: Context, action: String): PendingIntent = PendingIntent.getBroadcast(
        context,
        action.hashCode(),
        Intent(context, ReminderReceiver::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun nextDaily(hour: Int, now: LocalDateTime = LocalDateTime.now()): LocalDateTime {
        val today = now.toLocalDate().atTime(hour, 0)
        return if (today.isAfter(now)) today else today.plusDays(1)
    }

    private fun nextSunday(hour: Int, now: LocalDateTime = LocalDateTime.now()): LocalDateTime {
        val candidate = now.toLocalDate()
            .with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY))
            .atTime(hour, 0)
        return if (candidate.isAfter(now)) candidate else candidate.plusWeeks(1)
    }

    /** Texte du rappel du soir, ou null s'il n'y a rien à signaler. */
    fun eveningMessage(context: Context, data: HealthData): Pair<String, String>? {
        val goal = Prefs.goal(context)
        val week = data.nights.filterKeys { it > LocalDate.now().minusDays(7) }.values
        if (week.size < 3) return null
        val average = Duration.ofSeconds(week.sumOf { it.seconds } / week.size)
        if (average >= goal) return null
        val missing = goal.minus(average).toMinutes()
        return "Au lit ?" to
            "Moyenne des 7 derniers jours : ${formatDuration(average)}, " +
            "soit $missing min sous ton objectif de ${formatDuration(goal)}."
    }

    /**
     * Texte du résumé hebdomadaire.
     *
     * Si l'app a préparé un texte rédigé par Gemini Nano pendant qu'elle était ouverte, on
     * l'emploie. Impossible de le rédiger ici : AICore refuse l'inférence en arrière-plan,
     * et on arrive d'un BroadcastReceiver. [WeeklyBrief] ne rend son texte que s'il décrit
     * bien la semaine en cours, sinon on retombe sur le résumé calculé ci-dessous.
     */
    fun weeklyMessage(context: Context, data: HealthData): Pair<String, String>? {
        val computed = weeklyFallback(data) ?: return null
        return WeeklyBrief.load(context)?.let { "Ta semaine" to it } ?: computed
    }

    /** Le résumé gabarité, toujours disponible : il ne dépend d'aucun modèle. */
    fun weeklyFallback(data: HealthData): Pair<String, String>? {
        val today = LocalDate.now()
        val week = data.nights.filterKeys { it > today.minusDays(7) }.values
        if (week.isEmpty()) return null
        val average = Duration.ofSeconds(week.sumOf { it.seconds } / week.size)
        val previous = data.nights
            .filterKeys { it <= today.minusDays(7) && it > today.minusDays(14) }
            .values
        val steps = data.steps.filterKeys { it > today.minusDays(7) }.values

        val body = buildString {
            append("${formatDuration(average)} de sommeil en moyenne sur ${week.size} nuits")
            if (previous.isNotEmpty()) {
                val before = Duration.ofSeconds(previous.sumOf { it.seconds } / previous.size)
                val delta = average.minus(before).toMinutes()
                append(
                    when {
                        kotlin.math.abs(delta) < 6 -> ", comme la semaine dernière"
                        delta > 0 -> ", soit $delta min de plus que la semaine dernière"
                        else -> ", soit ${-delta} min de moins que la semaine dernière"
                    }
                )
            }
            append(".")
            if (steps.isNotEmpty()) {
                append(" ${formatSteps(steps.sum() / steps.size)} pas par jour.")
            }
        }
        return "Ta semaine" to body
    }

    fun notify(context: Context, id: Int, channelId: String, channelName: String, title: String, body: String) {
        if (!canNotify(context)) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_DEFAULT)
        )
        val open = PendingIntent.getActivity(
            context,
            id,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        manager.notify(id, notification)
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            Reminders.reschedule(context)
            return
        }
        if (action != ACTION_EVENING && action != ACTION_WEEKLY) return

        val pending = goAsync()
        val app = context.applicationContext
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val data = freshData(app)
                updateAllWidgets(app)
                when (action) {
                    ACTION_EVENING -> Reminders.eveningMessage(app, data)?.let { (title, body) ->
                        Reminders.notify(app, Reminders.NOTIFICATION_EVENING, Reminders.CHANNEL_EVENING, "Rappel du soir", title, body)
                    }
                    ACTION_WEEKLY -> Reminders.weeklyMessage(app, data)?.let { (title, body) ->
                        Reminders.notify(app, Reminders.NOTIFICATION_WEEKLY, Reminders.CHANNEL_WEEKLY, "Résumé hebdomadaire", title, body)
                    }
                }
            } finally {
                // Replace l'alarme suivante, même si la lecture a échoué.
                Reminders.reschedule(app)
                pending.finish()
            }
        }
    }
}

/**
 * Relit les trois dernières semaines dans Health Connect si la lecture en arrière-plan
 * est autorisée, et retombe sur le cache sinon.
 */
suspend fun freshData(context: Context): HealthData {
    val cached = DataCache.load(context)
    if (HealthConnectClient.getSdkStatus(context) != HealthConnectClient.SDK_AVAILABLE) return cached
    return runCatching {
        withTimeout(90_000) {
            val client = HealthConnectClient.getOrCreate(context)
            val granted = client.permissionController.getGrantedPermissions()
            if (PERMISSION_READ_BACKGROUND !in granted) return@withTimeout cached
            val today = LocalDate.now()
            val fresh = loadHealthData(client, granted, today.minusDays(21), today)
            val merged = cached + fresh.data
            DataCache.save(context, merged)
            merged
        }
    }.getOrDefault(cached)
}
