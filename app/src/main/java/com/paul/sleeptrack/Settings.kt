package com.paul.sleeptrack

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.paul.sleeptrack.ui.theme.AubeDimens
import com.paul.sleeptrack.ui.theme.LocalSleepColors
import com.paul.sleeptrack.ui.theme.TextRole
import com.paul.sleeptrack.ui.theme.ThemeChoice
import com.paul.sleeptrack.ui.theme.ThemeMode
import com.paul.sleeptrack.ui.theme.aubeOr
import com.paul.sleeptrack.ui.theme.sleepText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.LocalDate

/** Marqueur : la demande d'autorisation en cours vient du bouton d'exemple. */
private const val SAMPLE = "sample"

/** [onAppearanceChange] : le thème a changé (réglage, import ou remise à zéro), l'app le relit. */
@Composable
internal fun SettingsScreen(data: HealthData, onAppearanceChange: () -> Unit = {}) {
    val c = LocalSleepColors.current
    val context = LocalContext.current
    var evening by remember { mutableStateOf(Prefs.eveningEnabled(context)) }
    var eveningHour by remember { mutableIntStateOf(Prefs.eveningHour(context)) }
    var weekly by remember { mutableStateOf(Prefs.weeklyEnabled(context)) }
    var weeklyHour by remember { mutableIntStateOf(Prefs.weeklyHour(context)) }
    var pendingSwitch by remember { mutableStateOf<String?>(null) }
    var visible by remember { mutableStateOf(Prefs.visibleMetrics(context)) }
    var widgetMetric by remember { mutableStateOf(Prefs.widgetMetric(context)) }
    var display by remember { mutableStateOf(Prefs.display(context)) }
    var home by remember { mutableStateOf(Prefs.home(context)) }
    var recovery by remember { mutableStateOf(Prefs.recoveryConfig(context)) }
    var goals by remember { mutableStateOf(Prefs.goals(context)) }
    var appearance by remember { mutableStateOf(Prefs.appearance(context)) }
    var backupStatus by remember { mutableStateOf<String?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun reloadAll() {
        evening = Prefs.eveningEnabled(context)
        eveningHour = Prefs.eveningHour(context)
        weekly = Prefs.weeklyEnabled(context)
        weeklyHour = Prefs.weeklyHour(context)
        visible = Prefs.visibleMetrics(context)
        widgetMetric = Prefs.widgetMetric(context)
        display = Prefs.display(context)
        home = Prefs.home(context)
        recovery = Prefs.recoveryConfig(context)
        goals = Prefs.goals(context)
        appearance = Prefs.appearance(context)
        onAppearanceChange()
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            backupStatus = runCatching {
                withContext(Dispatchers.IO) {
                    // On verse d'abord l'année affichée dans l'archive : l'export porte
                    // alors tout ce que l'app connaît, pas seulement l'écran ouvert.
                    exportBackup(context, uri, Archive.merge(context, data))
                }
            }.fold(
                { "Exporté : $it jours dans le fichier choisi." },
                { "Export impossible (${it.message ?: "erreur inconnue"})." },
            )
        }
    }

    val csvLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            backupStatus = runCatching {
                withContext(Dispatchers.IO) {
                    exportCsv(context, uri, Archive.merge(context, data))
                }
            }.fold(
                { "Exporté : $it jours en CSV." },
                { "Export impossible (${it.message ?: "erreur inconnue"})." },
            )
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            backupStatus = runCatching {
                withContext(Dispatchers.IO) {
                    val imported = importBackup(context, uri)
                    Archive.merge(context, imported)
                    // Les jours récents relus dans Android reprennent la main sur ceux du
                    // fichier dès le retour à l'écran principal, comme avant.
                    forgetScreenTimeSync(context)
                    dayCount(imported)
                }
            }.fold(
                { days ->
                    // Le fichier a pu apporter un autre thème.
                    appearance = Prefs.appearance(context)
                    onAppearanceChange()
                    if (days == 0) {
                        "Aucun jour reconnu dans ce fichier."
                    } else {
                        "Importé : $days jours ajoutés à l'historique."
                    }
                },
                { "Import impossible (${it.message ?: "fichier illisible"})." },
            )
        }
    }

    fun persistReminders() {
        Prefs.of(context).edit()
            .putBoolean(Prefs.EVENING_ENABLED, evening)
            .putInt(Prefs.EVENING_HOUR, eveningHour)
            .putBoolean(Prefs.WEEKLY_ENABLED, weekly)
            .putInt(Prefs.WEEKLY_HOUR, weeklyHour)
            .apply()
        Reminders.reschedule(context)
    }

    fun sampleWeekly() {
        val message = Reminders.weeklyMessage(data)
            ?: ("Ta semaine" to "Pas encore assez de nuits enregistrées pour un résumé.")
        Reminders.notify(
            context, Reminders.NOTIFICATION_SAMPLE, Reminders.CHANNEL_WEEKLY,
            "Résumé hebdomadaire", message.first, message.second,
        )
    }

    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            when (pendingSwitch) {
                Prefs.EVENING_ENABLED -> evening = true
                Prefs.WEEKLY_ENABLED -> weekly = true
                SAMPLE -> sampleWeekly()
            }
            persistReminders()
        }
        pendingSwitch = null
    }

    // Activer un rappel sans pouvoir notifier ne servirait à rien : on demande d'abord.
    fun enable(key: String, turnOn: Boolean, apply: (Boolean) -> Unit) {
        if (turnOn && !Reminders.canNotify(context) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pendingSwitch = key
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        apply(turnOn)
        persistReminders()
    }

    Column(verticalArrangement = Arrangement.spacedBy(aubeOr(12.dp, AubeDimens.CardGap))) {
        Text("Réglages", color = c.ink, style = sleepText(TextRole.ScreenTitle, 20.sp, FontWeight.Bold))

        Section("Apparence") {
            SubHeading("Thème")
            ChoiceRow(
                options = ThemeChoice.entries.map { it.key to it.label },
                current = appearance.theme.key,
            ) { key ->
                Prefs.setTheme(context, ThemeChoice.of(key))
                appearance = Prefs.appearance(context)
                onAppearanceChange()
            }
            val modeEnabled = appearance.theme == ThemeChoice.AUBE
            SubHeading("Mode", enabled = modeEnabled)
            ChoiceRow(
                options = ThemeMode.entries.map { it.key to it.label },
                current = appearance.mode.key,
                enabled = modeEnabled,
            ) { key ->
                Prefs.setThemeMode(context, ThemeMode.of(key))
                appearance = Prefs.appearance(context)
                onAppearanceChange()
            }
            Text(
                "Le mode ne concerne qu'Aube : Clair affiche Aube, Sombre affiche Nuit tombée, " +
                    "Système suit le thème sombre d'Android.",
                color = c.ink3,
                style = sleepText(TextRole.Caption, 11.sp),
            )
        }

        Section("Accueil", startExpanded = true) {
            SettingSwitch(
                title = "Carte de récupération",
                subtitle = "Le score du matin, sur l'accueil",
                checked = home.card,
            ) { on -> Prefs.setHomeFlag(context, Prefs.HOME_CARD, on); home = Prefs.home(context) }
            if (home.card) {
                SettingSwitch(
                    title = "Jauge et verdict",
                    subtitle = "Le cercle et le mot (« Bien récupéré »…)",
                    checked = home.gauge,
                ) { on -> Prefs.setHomeFlag(context, Prefs.HOME_GAUGE, on); home = Prefs.home(context) }
                SettingSwitch(
                    title = "Détail des composantes",
                    subtitle = "Sommeil, VFC, pas de la veille sous le score",
                    checked = home.breakdown,
                ) { on -> Prefs.setHomeFlag(context, Prefs.HOME_BREAKDOWN, on); home = Prefs.home(context) }
            }
            SubHeading("Bandes affichées")
            Metric.entries.filter { it in visible }.forEach { m ->
                SettingSwitch(title = m.label, subtitle = "", checked = m in home.strips) { on ->
                    val next = if (on) home.strips + m else home.strips - m
                    Prefs.setHomeStrips(context, next)
                    home = Prefs.home(context)
                }
            }
            Stepper("Hauteur des bandes", Prefs.STRIP_SIZE_LABELS[home.stripSize]) { step ->
                Prefs.setHomeStripSize(context, home.stripSize + step)
                home = Prefs.home(context)
            }
            SettingSwitch(
                title = "Détail au toucher",
                subtitle = "Toucher une case de l'accueil ouvre le détail du jour",
                checked = home.tapDetail,
            ) { on -> Prefs.setHomeFlag(context, Prefs.HOME_TAP_DETAIL, on); home = Prefs.home(context) }
            SubHeading("Onglet d'ouverture")
            ChoiceRow(
                options = listOf("HOME" to "Accueil", "GRIDS" to "Grilles"),
                current = home.startTab,
            ) { next -> Prefs.setStartTab(context, next); home = Prefs.home(context) }
        }

        Section("Score de récupération") {
            Text(
                "Une composante désactivée sort du calcul ; le score devient la moyenne de celles " +
                    "qui restent actives.",
                color = c.ink2,
                style = sleepText(TextRole.Caption, 12.sp),
            )
            RecoveryComponent.entries.forEach { component ->
                SettingSwitch(title = component.label, subtitle = "", checked = component in recovery.components) { on ->
                    Prefs.setRecoveryComponent(context, component, on)
                    recovery = Prefs.recoveryConfig(context)
                }
            }
            val idx = BASELINE_CHOICES.indexOf(recovery.baselineDays).coerceAtLeast(0)
            Stepper("Période de référence", "${recovery.baselineDays} jours") { step ->
                val next = (idx + step).coerceIn(0, BASELINE_CHOICES.lastIndex)
                Prefs.setBaselineDays(context, BASELINE_CHOICES[next])
                recovery = Prefs.recoveryConfig(context)
            }
        }

        Section("Objectifs") {
            Stepper("Sommeil", formatDuration(Duration.ofMinutes(goals.sleepMinutes.toLong()))) { step ->
                val next = (goals.sleepMinutes + step * 15).coerceIn(300, 600)
                Prefs.setGoal(context, Prefs.GOAL_MINUTES, next)
                goals = Prefs.goals(context)
            }
            Stepper("Pas", formatSteps(goals.steps.toLong())) { step ->
                val next = (goals.steps + step * 500).coerceIn(4_000, 20_000)
                Prefs.setGoal(context, Prefs.GOAL_STEPS, next)
                goals = Prefs.goals(context)
            }
            Stepper("Écran", formatDuration(Duration.ofMinutes(goals.screenMinutes.toLong()))) { step ->
                val next = (goals.screenMinutes + step * 15).coerceIn(60, 360)
                Prefs.setGoal(context, Prefs.GOAL_SCREEN, next)
                goals = Prefs.goals(context)
            }
            Stepper("Récupération au vert", "${goals.recoveryGood}") { step ->
                val next = (goals.recoveryGood + step).coerceIn(50, 90)
                Prefs.setGoal(context, Prefs.GOAL_RECOVERY, next)
                goals = Prefs.goals(context)
            }
        }

        Section("Métriques affichées") {
            Text(
                "Masquer une métrique la retire des onglets, du détail d'une journée et des " +
                    "autorisations réclamées. Il en reste toujours au moins une.",
                color = c.ink2,
                style = sleepText(TextRole.Caption, 12.sp),
            )
            Metric.entries.forEach { entry ->
                val isVisible = entry in visible
                SettingSwitch(
                    title = entry.detailLabel,
                    subtitle = when (entry) {
                        Metric.STEPS -> "Masquer retire aussi le panneau « activité et sommeil »"
                        Metric.SCREEN -> "Lu dans Android, pas dans Health Connect"
                        Metric.RECOVERY -> "Le score, calculé à partir des composantes actives"
                        Metric.SLEEP -> "Visible dans le menu principal"
                    },
                    checked = isVisible,
                    enabled = !(isVisible && visible.size <= 1),
                ) { on ->
                    Prefs.setVisible(context, entry, on)
                    visible = Prefs.visibleMetrics(context)
                    widgetMetric = Prefs.widgetMetric(context)
                    home = Prefs.home(context)
                }
            }
        }

        Section("Grilles") {
            SettingSwitch(
                title = "Statistiques",
                subtitle = "Les quatre tuiles chiffrées sous la grille",
                checked = display.stats,
            ) { on -> Prefs.setFlag(context, Prefs.SHOW_STATS, on); display = Prefs.display(context) }
            SettingSwitch(
                title = "Séries",
                subtitle = "Jours consécutifs au-dessus de l'objectif, et record de l'année",
                checked = display.streaks,
            ) { on -> Prefs.setFlag(context, Prefs.SHOW_STREAKS, on); display = Prefs.display(context) }
            SettingSwitch(
                title = "Semaine type",
                subtitle = "La moyenne de chaque jour de la semaine",
                checked = display.week,
            ) { on -> Prefs.setFlag(context, Prefs.SHOW_WEEK, on); display = Prefs.display(context) }
            SettingSwitch(
                title = "Légende des couleurs",
                subtitle = "La bande de repères au bas de la grille",
                checked = display.legend,
            ) { on -> Prefs.setFlag(context, Prefs.SHOW_LEGEND, on); display = Prefs.display(context) }
            SettingSwitch(
                title = "Activité et sommeil",
                subtitle = "Le nuage de points et la corrélation",
                checked = display.correlation,
            ) { on -> Prefs.setFlag(context, Prefs.SHOW_CORRELATION, on); display = Prefs.display(context) }
            SettingSwitch(
                title = "Commentaires",
                subtitle = "Les petites phrases grises qui expliquent les panneaux",
                checked = display.notes,
            ) { on -> Prefs.setFlag(context, Prefs.SHOW_NOTES, on); display = Prefs.display(context) }
            SettingSwitch(
                title = "Détail du jour au toucher",
                subtitle = "Toucher une case de la grille ouvre le détail du jour",
                checked = display.dayDetail,
            ) { on -> Prefs.setFlag(context, Prefs.SHOW_DAY_DETAIL, on); display = Prefs.display(context) }
            SettingSwitch(
                title = "Bouton Partager",
                subtitle = "Exporter la grille de l'année en image",
                checked = display.showShare,
            ) { on -> Prefs.setFlag(context, Prefs.SHOW_SHARE, on); display = Prefs.display(context) }
            val sizeIndex = Prefs.CELL_SIZES.indexOf(display.cellSize).coerceAtLeast(0)
            Stepper("Taille des cases", Prefs.CELL_LABELS[sizeIndex]) { step ->
                val next = (sizeIndex + step).coerceIn(0, Prefs.CELL_SIZES.lastIndex)
                Prefs.setCellSize(context, Prefs.CELL_SIZES[next])
                display = Prefs.display(context)
            }
            SettingSwitch(
                title = "Agrandir en paysage",
                subtitle = "Cases à la hauteur de l'écran quand le téléphone est à l'horizontale",
                checked = display.landscapeBig,
            ) { on -> Prefs.setFlag(context, Prefs.LANDSCAPE_BIG, on); display = Prefs.display(context) }
        }

        Section("Rappels") {
            SettingSwitch(
                title = "Rappel du soir",
                subtitle = "Si la moyenne des 7 derniers jours passe sous l'objectif",
                checked = evening,
            ) { enable(Prefs.EVENING_ENABLED, it) { v -> evening = v } }
            if (evening) {
                Stepper("Heure", "%02dh00".format(eveningHour)) { step ->
                    eveningHour = (eveningHour + step + 24) % 24
                    persistReminders()
                }
            }
            SettingSwitch(
                title = "Résumé du dimanche",
                subtitle = "Moyenne de la semaine, comparée à la précédente",
                checked = weekly,
            ) { enable(Prefs.WEEKLY_ENABLED, it) { v -> weekly = v } }
            if (weekly) {
                Stepper("Heure", "%02dh00".format(weeklyHour)) { step ->
                    weeklyHour = (weeklyHour + step + 24) % 24
                    persistReminders()
                }
            }
            OutlinedButton(onClick = {
                if (Reminders.canNotify(context)) {
                    sampleWeekly()
                } else {
                    pendingSwitch = SAMPLE
                    notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }) {
                Text("Voir un exemple de résumé", color = c.ink)
            }
        }

        Section("Widget") {
            Text(
                "Ce que le widget affiche. Ajoute « Sleep Track » depuis l'écran des widgets ; " +
                    "il se redimensionne de 4x2 jusqu'à 2x1.",
                color = c.ink2,
                style = sleepText(TextRole.Body, 13.sp),
            )
            MetricSwitch(widgetMetric, visible) {
                widgetMetric = it
                Prefs.setWidgetMetric(context, it)
                updateAllWidgets(context)
            }
            OutlinedButton(onClick = { updateAllWidgets(context) }) {
                Text("Rafraîchir le widget", color = c.ink)
            }
        }

        Section("Sauvegarde") {
            Text(
                "Un fichier JSON lisible tel quel : une ligne par jour, avec minutes de sommeil, " +
                    "pas, écran et VFC. Le score de récupération n'y est pas : il se recalcule. " +
                    "L'app garde son propre historique, que l'export emporte en entier et que " +
                    "l'import complète sans rien écraser.",
                color = c.ink2,
                style = sleepText(TextRole.Body, 13.sp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        backupStatus = null
                        exportLauncher.launch("sleeptrack-${LocalDate.now()}.json")
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("Exporter", color = c.ink) }
                OutlinedButton(
                    onClick = {
                        backupStatus = null
                        importLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("Importer", color = c.ink) }
            }
            TextButton(onClick = {
                backupStatus = null
                csvLauncher.launch("sleeptrack-${LocalDate.now()}.csv")
            }) {
                Text("Exporter en CSV (pour un tableur)", color = c.ink2, style = sleepText(TextRole.Label, 13.sp))
            }
            backupStatus?.let { Text(it, color = c.positive, style = sleepText(TextRole.Caption, 13.sp)) }
            TextButton(onClick = {
                if (confirmClear) {
                    Archive.clear(context)
                    confirmClear = false
                    backupStatus = "Historique local effacé. Health Connect n'est pas touché."
                } else {
                    confirmClear = true
                }
            }) {
                Text(
                    if (confirmClear) "Confirmer l'effacement" else "Effacer l'historique local",
                    color = if (confirmClear) c.danger else c.ink2,
                    style = sleepText(TextRole.Label, 13.sp),
                )
            }
        }

        Panel {
            Column(Modifier.padding(aubeOr(16.dp, AubeDimens.CardPadding)), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = {
                    if (confirmReset) {
                        Prefs.resetSettings(context)
                        reloadAll()
                        confirmReset = false
                        backupStatus = null
                    } else {
                        confirmReset = true
                    }
                }) {
                    Text(
                        if (confirmReset) "Confirmer : tout remettre par défaut" else "Rétablir les réglages par défaut",
                        color = if (confirmReset) c.danger else c.ink2,
                        style = sleepText(TextRole.Label, 13.sp),
                    )
                }
                Text(
                    "Ne touche pas aux données : seulement à ce qui est réglable ici.",
                    color = c.ink3,
                    style = sleepText(TextRole.Caption, 11.sp),
                )
            }
        }

        Text(
            "Tout est calculé et gardé sur le téléphone. Rien ne part ailleurs, sauf le " +
                "fichier que tu exportes toi-même.",
            color = c.ink3,
            style = sleepText(TextRole.Caption, 11.sp),
        )
    }
}

/** Un panneau repliable, avec son titre et un chevron qui indique l'état. */
@Composable
private fun Section(title: String, startExpanded: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    val c = LocalSleepColors.current
    var expanded by remember { mutableStateOf(startExpanded) }
    Panel {
        Column(Modifier.padding(aubeOr(16.dp, AubeDimens.CardPadding)), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                Modifier.fillMaxWidth().clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    title,
                    color = c.ink,
                    style = sleepText(TextRole.SectionTitle, 16.sp, FontWeight.SemiBold),
                    modifier = Modifier.weight(1f),
                )
                Text(if (expanded) "▾" else "▸", color = c.ink2, fontSize = 16.sp)
            }
            if (expanded) content()
        }
    }
}

/** Intertitre dans une section : en capitales espacées en Aube, en demi-gras en Classique. */
@Composable
private fun SubHeading(text: String, enabled: Boolean = true) {
    val c = LocalSleepColors.current
    Text(
        if (c.isAube) text.uppercase() else text,
        color = when {
            !enabled -> if (c.isAube) c.ink3 else c.ink2
            c.isAube -> c.ink2
            else -> c.ink
        },
        style = sleepText(TextRole.Overline, classicWeight = FontWeight.SemiBold),
    )
}

/** Un choix exclusif entre quelques options : [options] associe une clé à son libellé. */
@Composable
private fun ChoiceRow(
    options: List<Pair<String, String>>,
    current: String,
    enabled: Boolean = true,
    onChange: (String) -> Unit,
) {
    val c = LocalSleepColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.38f)
            .background(c.surface2, RoundedCornerShape(14.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEach { (value, label) ->
            val active = value == current
            Box(
                Modifier
                    .weight(1f)
                    .background(
                        if (active) c.accent else Color.Transparent,
                        RoundedCornerShape(11.dp),
                    )
                    .clickable(enabled = enabled) { onChange(value) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    color = if (active) c.onAccent else c.ink2,
                    style = sleepText(TextRole.Label, classicWeight = if (active) FontWeight.SemiBold else FontWeight.Normal),
                )
            }
        }
    }
}

@Composable
internal fun SettingSwitch(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    val c = LocalSleepColors.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = if (enabled) c.ink else c.ink2, style = sleepText(TextRole.Body, 15.sp))
            if (subtitle.isNotEmpty()) Text(subtitle, color = c.ink2, style = sleepText(TextRole.Caption, 12.sp))
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = c.switchThumbOn,
                checkedTrackColor = c.accent,
            ),
        )
    }
}

@Composable
internal fun Stepper(label: String, value: String, onStep: (Int) -> Unit) {
    val c = LocalSleepColors.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = c.ink2, style = sleepText(TextRole.Body, 14.sp), modifier = Modifier.weight(1f))
        ArrowButton("‹", enabled = true) { onStep(-1) }
        Text(value, color = c.ink, style = sleepText(TextRole.Label, 16.sp, FontWeight.SemiBold))
        ArrowButton("›", enabled = true) { onStep(1) }
    }
}
