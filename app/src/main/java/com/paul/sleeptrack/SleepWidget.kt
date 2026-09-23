package com.paul.sleeptrack

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.RemoteViews
import com.paul.sleeptrack.ui.theme.AubeTokens
import java.time.LocalDate
import java.time.ZoneId

private const val ACTION_MIDNIGHT = "com.paul.sleeptrack.WIDGET_MIDNIGHT"

/**
 * Widget d'écran d'accueil : les dernières semaines de la métrique choisie dans l'app.
 * Il dessine à partir du cache local, donc il reste lisible même quand Health Connect
 * n'est pas interrogeable en arrière-plan.
 *
 * Pas de rafraîchissement périodique : l'image ne dépend que du cache, de la métrique et
 * de la date. L'app et les rappels le redessinent quand le cache change ; il reste le
 * passage à minuit, confié à une alarme qui ne réveille pas le téléphone.
 */
class SleepWidget : AppWidgetProvider() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            // Nouveau jour, ou horloge déplacée : la bande glisse d'une case.
            ACTION_MIDNIGHT, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED ->
                updateAllWidgets(context)
            else -> super.onReceive(context, intent)
        }
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { renderWidget(context, manager, it) }
        scheduleMidnight(context)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        manager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle,
    ) {
        renderWidget(context, manager, appWidgetId)
    }

    override fun onDisabled(context: Context) {
        context.getSystemService(AlarmManager::class.java)?.cancel(midnightIntent(context))
    }
}

/**
 * Un seul dessin à la fois : l'app, les rappels et le système peuvent redessiner depuis
 * des fils différents, et le dernier arrivé doit être celui qui a lu le cache le plus récent.
 */
private val widgetLock = Any()

/** Jour du dernier dessin de tous les widgets. */
@Volatile
private var drawnOn: LocalDate? = null

/**
 * Le widget ne dépend que du cache, de la métrique choisie et de la date. Quand le cache
 * n'a pas changé et qu'il a déjà été dessiné aujourd'hui, le redessiner donnerait la même image.
 */
fun widgetsDrawnToday(): Boolean = drawnOn == LocalDate.now()

/** Redessine tous les widgets posés, après un chargement ou un changement de métrique. */
fun updateAllWidgets(context: Context) {
    val manager = AppWidgetManager.getInstance(context) ?: return
    val ids = manager.getAppWidgetIds(ComponentName(context, SleepWidget::class.java))
    ids.forEach { renderWidget(context, manager, it) }
    drawnOn = LocalDate.now()
    if (ids.isNotEmpty()) scheduleMidnight(context)
}

/**
 * Prochain minuit, en RTC et non RTC_WAKEUP : l'alarme attend que le téléphone se réveille
 * de lui-même, donc au plus tard quand l'écran s'allume, c'est-à-dire quand le widget se
 * regarde. Reposée à chaque dessin ; la même PendingIntent remplace la précédente.
 */
private fun scheduleMidnight(context: Context) {
    val alarms = context.getSystemService(AlarmManager::class.java) ?: return
    val next = LocalDate.now().plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    alarms.set(AlarmManager.RTC, next, midnightIntent(context))
}

private fun midnightIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
    context,
    0,
    Intent(context, SleepWidget::class.java).setAction(ACTION_MIDNIGHT),
    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
)

private fun renderWidget(context: Context, manager: AppWidgetManager, appWidgetId: Int) = synchronized(widgetLock) {
    val options = manager.getAppWidgetOptions(appWidgetId)
    val density = context.resources.displayMetrics.density
    val widthDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0).takeIf { it > 0 } ?: 250
    val heightDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0).takeIf { it > 0 } ?: 110

    // Les RemoteViews transportent le bitmap : on plafonne sa taille pour rester
    // largement sous la limite de transaction du système.
    val widthPx = (widthDp * density).toInt().coerceIn(120, 1_000)
    val heightPx = (heightDp * density).toInt().coerceIn(60, 600)

    val metric = Prefs.widgetMetric(context)
    val data = DataCache.load(context).withRecovery(Prefs.recoveryConfig(context))
    val bitmap = renderStrip(metric, data, widthPx, heightPx, AubeTokens.Classique, goals = Prefs.goals(context))

    val open = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    val views = RemoteViews(context.packageName, R.layout.widget_sleep).apply {
        setImageViewBitmap(R.id.widget_image, bitmap)
        setOnClickPendingIntent(R.id.widget_image, open)
    }
    manager.updateAppWidget(appWidgetId, views)
}
