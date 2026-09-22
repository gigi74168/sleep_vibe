package com.paul.sleeptrack

import android.Manifest
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.LocalDate

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // À chaque lancement, mais pas à chaque rotation : l'activité recréée n'a rien à
        // reposer. Un arrêt forcé annule les alarmes et ferme aussi l'activité, donc le
        // lancement suivant repasse bien par ici.
        if (savedInstanceState == null) {
            Reminders.reschedule(this)
            // Le résumé du dimanche rédigé par le modèle a été retiré en 2.7 : on efface le
            // dernier texte préparé, dérivé des données de santé, que plus rien ne lira.
            val prefs = Prefs.of(this)
            if (prefs.contains("ai_weekly_text") || prefs.contains("ai_weekly_covers")) {
                prefs.edit().remove("ai_weekly_text").remove("ai_weekly_covers").apply()
            }
        }
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(background = Palette.bg, surface = Palette.card)) {
                SleepApp()
            }
        }
    }
}

private sealed interface UiState {
    data object Loading : UiState
    data object NotInstalled : UiState
    data object NeedsPermission : UiState
    data class Ready(val data: HealthData, val missing: Set<String>) : UiState
    data class Error(val message: String) : UiState
}

@Composable
private fun SleepApp() {
    val context = LocalContext.current
    var year by remember { mutableIntStateOf(LocalDate.now().year) }
    var metric by remember { mutableStateOf(Prefs.lastMetric(context)) }
    var visibleMetrics by remember { mutableStateOf(Prefs.visibleMetrics(context)) }
    var demo by remember { mutableStateOf(false) }
    var settings by remember { mutableStateOf(false) }
    var refreshKey by remember { mutableIntStateOf(0) }
    var display by remember { mutableStateOf(Prefs.display(context)) }
    var state by remember { mutableStateOf<UiState>(UiState.Loading) }
    val scope = rememberCoroutineScope()

    val permissionLauncher = rememberLauncherForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { refreshKey++ }

    // Relecture à chaque retour dans l'app. L'inscription rejoue les événements jusqu'à
    // l'état courant : le ON_RESUME reçu à ce moment-là n'est pas un retour, et le
    // chargement initial est déjà parti. Le compter lançait tout le chargement deux fois
    // au démarrage, en parallèle.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        var replaying = true
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && !replaying) refreshKey++
        }
        lifecycle.addObserver(observer)
        replaying = false
        onDispose { lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(year, demo, refreshKey) {
        if (demo) {
            state = UiState.Ready(demoHealthData(year), emptySet())
            return@LaunchedEffect
        }
        // Ce que l'app a déjà lu ou importé : de quoi afficher une année même si Health
        // Connect ne la restitue plus, ou pas encore. Le temps d'écran y est versé d'abord :
        // Android n'en garde qu'une dizaine de jours, l'archive fait le reste.
        val archived = withContext(Dispatchers.IO) {
            if (Metric.SCREEN in visibleMetrics) syncScreenTime(context)
            Archive.load(context).filterYear(year)
        }
        if (HealthConnectClient.getSdkStatus(context) != HealthConnectClient.SDK_AVAILABLE) {
            state = if (archived.isEmpty()) UiState.NotInstalled else UiState.Ready(archived, emptySet())
            return@LaunchedEffect
        }
        val client = HealthConnectClient.getOrCreate(context)
        state = try {
            val granted = client.permissionController.getGrantedPermissions()
            if (DATA_PERMISSIONS.values.none { it in granted }) {
                if (archived.isEmpty()) {
                    UiState.NeedsPermission
                } else {
                    UiState.Ready(archived, REQUESTED_PERMISSIONS - granted)
                }
            } else {
                // Health Connect a le dernier mot sur les jours qu'il connaît ; l'archive
                // comble le reste.
                val data = archived + loadYear(client, granted, year)
                withContext(Dispatchers.IO) {
                    Archive.merge(context, data)
                    // Le widget et les rappels lisent ce cache : on le rafraîchit à chaque
                    // passage sur l'année en cours. Le widget n'est redessiné que si son
                    // image peut avoir changé : nouveau contenu, ou nouveau jour.
                    if (year == LocalDate.now().year) {
                        val changed = DataCache.save(context, data)
                        if (changed || !widgetsDrawnToday()) updateAllWidgets(context)
                    }
                }
                UiState.Ready(data, REQUESTED_PERMISSIONS - granted)
            }
        } catch (e: Exception) {
            UiState.Error(e.message ?: e.javaClass.simpleName)
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Palette.bg)
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        when {
            settings -> SettingsScreen(
                data = (state as? UiState.Ready)?.data ?: DataCache.load(context),
                onBack = {
                    settings = false
                    visibleMetrics = Prefs.visibleMetrics(context)
                    display = Prefs.display(context)
                    if (metric !in visibleMetrics) metric = Metric.SLEEP
                    // Dessiné hors du fil de l'interface : le retour à l'écran principal
                    // n'attend plus le rendu des images du widget.
                    val app = context.applicationContext
                    scope.launch(Dispatchers.IO) { updateAllWidgets(app) }
                    // Un import a pu enrichir l'archive : on relit.
                    refreshKey++
                },
            )
            else -> when (val s = state) {
                UiState.Loading -> Box(Modifier.fillMaxWidth().padding(top = 120.dp), Alignment.Center) {
                    CircularProgressIndicator(color = Palette.levels.last())
                }
                UiState.NotInstalled -> Message(
                    title = "Health Connect n'est pas disponible",
                    body = "Installe ou mets à jour Health Connect depuis le Play Store, puis reviens ici.",
                    action = "Ouvrir le Play Store",
                    onAction = {
                        val uri = Uri.parse("market://details?id=com.google.android.apps.healthdata&url=healthconnect%3A%2F%2Fonboarding")
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, uri).setPackage("com.android.vending"))
                        }
                    },
                    onDemo = { demo = true },
                )
                UiState.NeedsPermission -> Message(
                    title = "Accès à tes données de santé",
                    body = "Sommeil lit tes nuits, tes pas, ton cœur au repos et ton poids dans Health " +
                        "Connect pour dessiner tes grilles. Rien ne quitte ton téléphone.",
                    action = "Autoriser l'accès",
                    onAction = { permissionLauncher.launch(requestedPermissions(visibleMetrics)) },
                    onDemo = { demo = true },
                )
                is UiState.Error -> Message(
                    title = "Erreur de lecture",
                    body = s.message,
                    action = "Réessayer",
                    onAction = { refreshKey++ },
                    onDemo = { demo = true },
                )
                is UiState.Ready -> MainScreen(
                    year = year,
                    metric = metric,
                    visibleMetrics = visibleMetrics,
                    display = display,
                    data = s.data,
                    missing = s.missing,
                    demo = demo,
                    onYear = { year = it },
                    onMetric = {
                        metric = it
                        Prefs.setLastMetric(context, it)
                    },
                    onRequestPermissions = { permissionLauncher.launch(requestedPermissions(visibleMetrics)) },
                    onExitDemo = { demo = false },
                    onSettings = { settings = true },
                )
            }
        }
    }
}

@Composable
private fun MainScreen(
    year: Int,
    metric: Metric,
    visibleMetrics: List<Metric>,
    display: DisplayPrefs,
    data: HealthData,
    missing: Set<String>,
    demo: Boolean,
    onYear: (Int) -> Unit,
    onMetric: (Metric) -> Unit,
    onRequestPermissions: () -> Unit,
    onExitDemo: () -> Unit,
    onSettings: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selected by remember(year) { mutableStateOf<LocalDate?>(null) }
    // Commentaires désactivés : ni client ML Kit, ni interrogation d'AICore.
    val nano = if (display.ai) rememberNano() else null
    val today = LocalDate.now()
    val currentYear = today.year
    val scale = remember(metric, data) { scaleFor(metric, data) }
    val series = remember(metric, data) { data.series(metric) }
    val config = LocalConfiguration.current
    // À l'horizontale, l'écran est large et court : on donne aux cases la hauteur
    // disponible et la grille défile latéralement.
    val minPitch = remember(config.orientation, config.screenHeightDp, display) {
        val landscape = config.orientation == Configuration.ORIENTATION_LANDSCAPE
        val fromHeight = if (landscape && display.landscapeBig) {
            ((config.screenHeightDp - 150) / 7).coerceIn(14, 30)
        } else {
            0
        }
        maxOf(display.cellSize, fromHeight).dp
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Sommeil", color = Palette.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { scope.launch { shareYearImage(context, year, metric, data) } }) {
                Text("Partager", color = Palette.muted, fontSize = 14.sp)
            }
            TextButton(onClick = onSettings) {
                Text("Réglages", color = Palette.muted, fontSize = 14.sp)
            }
        }

        if (demo) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Mode démo (données fictives)", color = Palette.levels[2], fontSize = 13.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = onExitDemo) { Text("Quitter", color = Palette.text) }
            }
        }

        if (visibleMetrics.size > 1) MetricSwitch(metric, visibleMetrics, onMetric)

        Panel {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ArrowButton("‹", enabled = true) { onYear(year - 1) }
                    Text("$year", fontSize = 34.sp, fontWeight = FontWeight.Bold, color = Palette.text)
                    ArrowButton("›", enabled = year < currentYear) { onYear(year + 1) }
                    Spacer(Modifier.weight(1f))
                    Text(metric.countLabel(series.size), fontSize = 18.sp, color = Palette.muted)
                }
                YearHeatmap(
                    year = year,
                    selected = selected,
                    onSelect = { selected = it },
                    colorAt = { day -> series[day]?.let(scale::colorOf) },
                    minPitch = minPitch,
                )
                if (display.legend) Legend(metric, scale)
            }
        }

        selected?.let { day -> DayDetail(day, data, visibleMetrics) }

        if (metric == Metric.SCREEN && !demo && !hasUsageAccess(context)) {
            Panel {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Le temps d'écran ne vient pas de Health Connect mais d'Android. Autorise " +
                            "Sommeil dans « Accès aux données d'utilisation », puis reviens ici.",
                        color = Palette.muted,
                        fontSize = 13.sp,
                    )
                    OutlinedButton(onClick = { openUsageAccessSettings(context) }) {
                        Text("Ouvrir le réglage", color = Palette.text)
                    }
                    if (display.notes) {
                        Text(
                            "Android n'en garde qu'une dizaine de jours : l'historique commence là, " +
                                "puis s'allonge à chaque ouverture de l'app.",
                            color = Palette.muted.copy(alpha = 0.7f),
                            fontSize = 11.sp,
                        )
                    }
                }
            }
        }

        if (series.isEmpty()) {
            if (display.notes) EmptyNote("Aucune donnée « ${metric.label.lowercase()} » pour cette année.")
        } else {
            // Mémorisées : toucher une case recompose l'écran, pas les chiffres. La date
            // fait partie des clés, les tuiles « 7 derniers jours » et « série en cours » en
            // dépendent.
            if (display.stats) {
                val tiles = remember(metric, data, scale, today) { statTiles(metric, data, scale) }
                StatRow(tiles[0], tiles[1])
                StatRow(tiles[2], tiles[3])
            }
            if (display.streaks) {
                val streaks = remember(metric, data, display.goalMinutes, year, today) {
                    streakTiles(metric, data, display.goalMinutes, year, today)
                }
                streaks?.let { (current, best) ->
                    StatRow(current, best)
                    if (display.notes) {
                        targetFor(metric, display.goalMinutes)?.let { target ->
                            EmptyNote("Séries de ${target.label} ; un jour sans donnée la coupe.")
                        }
                    }
                }
            }
            if (display.week) {
                Panel { WeekProfile(metric, data, scale, showNote = display.notes) }
            }
        }

        if (display.correlation && Metric.STEPS in visibleMetrics &&
            data.steps.isNotEmpty() && data.nights.isNotEmpty()
        ) {
            Panel { CorrelationPanel(data, showNotes = display.notes) }
        }

        if (nano != null) {
            // Le commentaire vient après les chiffres : il les croise, il ne les annonce pas.
            if (nano.ready && series.isNotEmpty()) {
                Panel { NanoComment(nano, metric, data, display.goalMinutes, year) }
            }
            // Ne s'affiche que si Nano est supporté mais pas encore téléchargé.
            if (nano.status == NanoStatus.DOWNLOADABLE || nano.status == NanoStatus.DOWNLOADING) {
                Panel { NanoDownloadPanel(nano) }
            }
        }

        val relevantMissing = remember(missing, visibleMetrics) {
            requestedPermissions(visibleMetrics).intersect(missing)
        }
        if (relevantMissing.isNotEmpty() && !demo) {
            Panel {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(missingText(relevantMissing), color = Palette.muted, fontSize = 13.sp)
                    OutlinedButton(onClick = onRequestPermissions) {
                        Text("Compléter les autorisations", color = Palette.text)
                    }
                }
            }
        }
    }
}

private fun missingText(missing: Set<String>): String {
    val parts = DATA_PERMISSIONS.filterValues { it in missing }.keys.map { it.detailLabel.lowercase() }
    val extras = buildList {
        if (PERMISSION_READ_HISTORY in missing) add("l'historique au-delà de 30 jours")
        if (PERMISSION_READ_BACKGROUND in missing) add("la lecture en arrière-plan (widget et rappels)")
    }
    return buildString {
        if (parts.isNotEmpty()) {
            append("Health Connect ne partage pas encore ${parts.joinToString(", ")}.")
        }
        if (extras.isNotEmpty()) {
            if (isNotEmpty()) append(" ")
            append("Il manque aussi ${extras.joinToString(" et ")}.")
        }
    }
}

@Composable
private fun MetricSwitch(metric: Metric, entries: List<Metric>, onMetric: (Metric) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Palette.card, RoundedCornerShape(14.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        entries.forEach { entry ->
            val active = entry == metric
            Box(
                Modifier
                    .weight(1f)
                    .background(
                        if (active) Palette.empty else Color.Transparent,
                        RoundedCornerShape(11.dp),
                    )
                    .clickable { onMetric(entry) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    entry.label,
                    color = if (active) Palette.text else Palette.muted,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    fontSize = if (entries.size > 4) 12.sp else 14.sp,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}

@Composable
private fun ArrowButton(symbol: String, enabled: Boolean, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        contentPadding = PaddingValues(0.dp),
        modifier = Modifier.width(36.dp),
    ) {
        Text(symbol, fontSize = 30.sp, color = if (enabled) Palette.muted else Palette.card)
    }
}

@Composable
private fun Panel(content: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .background(Palette.card, RoundedCornerShape(20.dp))
    ) { content() }
}

@Composable
private fun DayDetail(day: LocalDate, data: HealthData, visibleMetrics: List<Metric>) {
    Panel {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                day.format(Palette.longDate).replaceFirstChar { it.uppercase() },
                color = Palette.text,
                fontWeight = FontWeight.SemiBold,
            )
            visibleMetrics.forEach { entry ->
                val value = data.series(entry)[day]
                DetailLine(
                    entry.detailLabel,
                    value?.let(entry::format) ?: "Aucune donnée",
                    value?.let { scaleFor(entry, data).colorOf(it) },
                )
            }
        }
    }
}

@Composable
private fun DetailLine(label: String, value: String, color: Color?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(14.dp).background(color ?: Palette.empty, RoundedCornerShape(4.dp)))
        Spacer(Modifier.width(10.dp))
        Text(label, color = Palette.muted, modifier = Modifier.weight(1f), fontSize = 14.sp)
        Text(value, color = color ?: Palette.muted, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
    }
}

@Composable
private fun StatRow(left: StatTile, right: StatTile) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        StatTileView(left, Modifier.weight(1f))
        StatTileView(right, Modifier.weight(1f))
    }
}

@Composable
private fun StatTileView(tile: StatTile, modifier: Modifier) {
    Box(modifier.background(Palette.card, RoundedCornerShape(16.dp)).padding(14.dp)) {
        Column {
            Text(tile.label, color = Palette.muted, fontSize = 12.sp)
            Text(tile.value, color = tile.color, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}

@Composable
private fun EmptyNote(text: String) {
    Text(text, color = Palette.muted, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
}

@Composable
private fun Message(title: String, body: String, action: String, onAction: () -> Unit, onDemo: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(top = 80.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(title, color = Palette.text, fontSize = 22.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Text(body, color = Palette.muted, fontSize = 15.sp, textAlign = TextAlign.Center)
        Button(
            onClick = onAction,
            colors = ButtonDefaults.buttonColors(containerColor = Palette.levels.last(), contentColor = Palette.bg),
        ) { Text(action) }
        TextButton(onClick = onDemo) { Text("Voir une démo", color = Palette.muted) }
    }
}

// ---------------------------------------------------------------- Réglages

/** Marqueur : la demande d'autorisation en cours vient du bouton d'exemple. */
private const val SAMPLE = "sample"

@Composable
private fun SettingsScreen(data: HealthData, onBack: () -> Unit) {
    val context = LocalContext.current
    var evening by remember { mutableStateOf(Prefs.eveningEnabled(context)) }
    var eveningHour by remember { mutableIntStateOf(Prefs.eveningHour(context)) }
    var weekly by remember { mutableStateOf(Prefs.weeklyEnabled(context)) }
    var weeklyHour by remember { mutableIntStateOf(Prefs.weeklyHour(context)) }
    var goal by remember { mutableIntStateOf(Prefs.goalMinutes(context)) }
    var pendingSwitch by remember { mutableStateOf<String?>(null) }
    var visible by remember { mutableStateOf(Prefs.visibleMetrics(context)) }
    var widgetMetric by remember { mutableStateOf(Prefs.widgetMetric(context)) }
    var display by remember { mutableStateOf(Prefs.display(context)) }
    var backupStatus by remember { mutableStateOf<String?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val nano = rememberNano()

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

    fun persist() {
        Prefs.of(context).edit()
            .putBoolean(Prefs.EVENING_ENABLED, evening)
            .putInt(Prefs.EVENING_HOUR, eveningHour)
            .putBoolean(Prefs.WEEKLY_ENABLED, weekly)
            .putInt(Prefs.WEEKLY_HOUR, weeklyHour)
            .putInt(Prefs.GOAL_MINUTES, goal)
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
            persist()
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
        persist()
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Réglages", color = Palette.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onBack) { Text("Retour", color = Palette.muted) }
        }

        Panel {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SettingSwitch(
                    title = "Rappel du soir",
                    subtitle = "Si la moyenne des 7 derniers jours passe sous l'objectif",
                    checked = evening,
                    onChange = { enable(Prefs.EVENING_ENABLED, it) { v -> evening = v } },
                )
                if (evening) {
                    Stepper("Heure", "%02dh00".format(eveningHour)) { step ->
                        eveningHour = (eveningHour + step + 24) % 24
                        persist()
                    }
                }
                Stepper("Objectif de sommeil", formatDuration(Duration.ofMinutes(goal.toLong()))) { step ->
                    goal = (goal + step * 15).coerceIn(300, 600)
                    persist()
                }
            }
        }

        Panel {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SettingSwitch(
                    title = "Résumé du dimanche",
                    subtitle = "Moyenne de la semaine, comparée à la précédente",
                    checked = weekly,
                    onChange = { enable(Prefs.WEEKLY_ENABLED, it) { v -> weekly = v } },
                )
                if (weekly) {
                    Stepper("Heure", "%02dh00".format(weeklyHour)) { step ->
                        weeklyHour = (weeklyHour + step + 24) % 24
                        persist()
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
                    Text("Voir un exemple de résumé", color = Palette.text)
                }
            }
        }

        Panel {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Métriques affichées", color = Palette.text, fontWeight = FontWeight.SemiBold)
                Text(
                    "Masquer une métrique la retire des onglets, du détail d'une journée " +
                        "et des autorisations réclamées.",
                    color = Palette.muted,
                    fontSize = 12.sp,
                )
                Metric.entries.filter { it.canHide }.forEach { entry ->
                    SettingSwitch(
                        title = entry.detailLabel,
                        subtitle = when (entry) {
                            Metric.STEPS -> "Masquer retire aussi le panneau « activité et sommeil »"
                            Metric.SCREEN -> "Lu dans Android, pas dans Health Connect"
                            else -> "Visible dans le menu principal"
                        },
                        checked = entry in visible,
                        onChange = { on ->
                            Prefs.setVisible(context, entry, on)
                            visible = Prefs.visibleMetrics(context)
                            widgetMetric = Prefs.widgetMetric(context)
                        },
                    )
                }
                Text(
                    "Le sommeil reste toujours affiché : c'est le sujet de l'app.",
                    color = Palette.muted.copy(alpha = 0.7f),
                    fontSize = 11.sp,
                )
            }
        }

        Panel {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Widget", color = Palette.text, fontWeight = FontWeight.SemiBold)
                Text(
                    "Ce que le widget affiche. Ajoute « Sommeil » depuis l'écran des widgets ; " +
                        "il se redimensionne de 4x2 jusqu'à 2x1.",
                    color = Palette.muted,
                    fontSize = 13.sp,
                )
                MetricSwitch(widgetMetric, visible) {
                    widgetMetric = it
                    Prefs.setWidgetMetric(context, it)
                    updateAllWidgets(context)
                }
                OutlinedButton(onClick = { updateAllWidgets(context) }) {
                    Text("Rafraîchir le widget", color = Palette.text)
                }
            }
        }

        Panel {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Affichage", color = Palette.text, fontWeight = FontWeight.SemiBold)
                SettingSwitch(
                    title = "Statistiques",
                    subtitle = "Les quatre tuiles chiffrées sous la grille",
                    checked = display.stats,
                ) { on ->
                    Prefs.setFlag(context, Prefs.SHOW_STATS, on)
                    display = Prefs.display(context)
                }
                SettingSwitch(
                    title = "Séries",
                    subtitle = "Jours consécutifs au-dessus de l'objectif, et record de l'année",
                    checked = display.streaks,
                ) { on ->
                    Prefs.setFlag(context, Prefs.SHOW_STREAKS, on)
                    display = Prefs.display(context)
                }
                SettingSwitch(
                    title = "Semaine type",
                    subtitle = "La moyenne de chaque jour de la semaine",
                    checked = display.week,
                ) { on ->
                    Prefs.setFlag(context, Prefs.SHOW_WEEK, on)
                    display = Prefs.display(context)
                }
                SettingSwitch(
                    title = "Légende des couleurs",
                    subtitle = "La bande de repères au bas de la grille",
                    checked = display.legend,
                ) { on ->
                    Prefs.setFlag(context, Prefs.SHOW_LEGEND, on)
                    display = Prefs.display(context)
                }
                SettingSwitch(
                    title = "Activité et sommeil",
                    subtitle = "Le nuage de points et la corrélation",
                    checked = display.correlation,
                ) { on ->
                    Prefs.setFlag(context, Prefs.SHOW_CORRELATION, on)
                    display = Prefs.display(context)
                }
                SettingSwitch(
                    title = "Commentaires",
                    subtitle = "Les petites phrases grises qui expliquent les panneaux",
                    checked = display.notes,
                ) { on ->
                    Prefs.setFlag(context, Prefs.SHOW_NOTES, on)
                    display = Prefs.display(context)
                }
                SettingSwitch(
                    title = "Commentaires du modèle local",
                    subtitle = if (nano.ready) {
                        "Deux phrases rédigées sur le téléphone, sous les statistiques"
                    } else {
                        "Ce téléphone ne fait pas tourner le modèle : sans effet ici"
                    },
                    checked = display.ai,
                ) { on ->
                    Prefs.setFlag(context, Prefs.SHOW_AI, on)
                    display = Prefs.display(context)
                }
            }
        }

        Panel {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Taille de la grille", color = Palette.text, fontWeight = FontWeight.SemiBold)
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
                ) { on ->
                    Prefs.setFlag(context, Prefs.LANDSCAPE_BIG, on)
                    display = Prefs.display(context)
                }
                Text(
                    "Quand les cases ne tiennent plus dans la largeur, la grille défile " +
                        "latéralement ; la colonne des jours, elle, reste en place.",
                    color = Palette.muted,
                    fontSize = 12.sp,
                )
            }
        }

        Panel {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Sauvegarde", color = Palette.text, fontWeight = FontWeight.SemiBold)
                Text(
                    "Un fichier JSON lisible tel quel : une ligne par jour, avec minutes de " +
                        "sommeil, pas, cœur au repos et poids. L'app garde son propre historique, " +
                        "que l'export emporte en entier et que l'import complète sans rien écraser.",
                    color = Palette.muted,
                    fontSize = 13.sp,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            backupStatus = null
                            exportLauncher.launch("sommeil-${LocalDate.now()}.json")
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("Exporter", color = Palette.text) }
                    OutlinedButton(
                        onClick = {
                            backupStatus = null
                            importLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("Importer", color = Palette.text) }
                }
                TextButton(onClick = {
                    backupStatus = null
                    csvLauncher.launch("sommeil-${LocalDate.now()}.csv")
                }) {
                    Text("Exporter en CSV (pour un tableur)", color = Palette.muted, fontSize = 13.sp)
                }
                backupStatus?.let { Text(it, color = Palette.levels[3], fontSize = 13.sp) }
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
                        color = if (confirmClear) Palette.levels[0] else Palette.muted,
                        fontSize = 13.sp,
                    )
                }
            }
        }

        Text(
            "Tout est calculé et gardé sur le téléphone. Rien ne part ailleurs, sauf le " +
                "fichier que tu exportes toi-même.",
            color = Palette.muted.copy(alpha = 0.7f),
            fontSize = 11.sp,
        )
    }
}

@Composable
private fun SettingSwitch(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Palette.text, fontSize = 15.sp)
            Text(subtitle, color = Palette.muted, fontSize = 12.sp)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Palette.bg,
                checkedTrackColor = Palette.levels.last(),
            ),
        )
    }
}

@Composable
private fun Stepper(label: String, value: String, onStep: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Palette.muted, fontSize = 14.sp, modifier = Modifier.weight(1f))
        ArrowButton("‹", enabled = true) { onStep(-1) }
        Text(value, color = Palette.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        ArrowButton("›", enabled = true) { onStep(1) }
    }
}
