package com.paul.sleeptrack

import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.paul.sleeptrack.ui.theme.AubeDimens
import com.paul.sleeptrack.ui.theme.AubeTokens
import com.paul.sleeptrack.ui.theme.LocalSleepColors
import com.paul.sleeptrack.ui.theme.SleepTheme
import com.paul.sleeptrack.ui.theme.TextRole
import com.paul.sleeptrack.ui.theme.aubeCard
import com.paul.sleeptrack.ui.theme.aubeOr
import com.paul.sleeptrack.ui.theme.sleepText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // À chaque lancement, mais pas quand l'activité est seulement recréée (langue, thème,
        // retour après la mort du processus) : il n'y a rien à reposer. Un arrêt forcé annule
        // les alarmes et ferme aussi l'activité, donc le lancement suivant repasse par ici.
        if (savedInstanceState == null) {
            Reminders.reschedule(this)
            // Le modèle local a été retiré (résumé du dimanche en 2.7, le reste ensuite) : on
            // efface le dernier texte préparé, dérivé des données de santé, et le réglage
            // qui ne commande plus rien.
            val prefs = Prefs.of(this)
            val stale = listOf("ai_weekly_text", "ai_weekly_covers", "show_ai").filter(prefs::contains)
            if (stale.isNotEmpty()) {
                val editor = prefs.edit()
                stale.forEach { editor.remove(it) }
                editor.apply()
            }
        }
        enableEdgeToEdge()
        setContent { SleepRoot() }
    }
}

private sealed interface UiState {
    data object Loading : UiState
    data object NotInstalled : UiState
    data object NeedsPermission : UiState
    /** [data] : l'année de la grille ; [recent] : les derniers mois, pour l'accueil. */
    data class Ready(val data: HealthData, val recent: HealthData, val missing: Set<String>) : UiState
    data class Error(val message: String) : UiState
}

private enum class Tab(val label: String, val icon: Int) {
    HOME("Accueil", R.drawable.ic_tab_home),
    GRIDS("Grilles", R.drawable.ic_tab_grid),
    SETTINGS("Réglages", R.drawable.ic_tab_settings),
}

/** Ce que l'accueil garde : de quoi remplir ses bandeaux, qui couvrent quelques mois. */
private const val RECENT_DAYS = 200L

/** Le thème se choisit dans les réglages ; en mode Système, Aube suit le thème sombre d'Android. */
@Composable
private fun SleepRoot() {
    val context = LocalContext.current
    var appearance by remember { mutableStateOf(Prefs.appearance(context)) }
    val colors = AubeTokens.resolve(appearance.theme, appearance.mode, isSystemInDarkTheme())
    SleepTheme(colors) { SleepApp(onAppearanceChange = { appearance = Prefs.appearance(context) }) }
}

@Composable
private fun SleepApp(onAppearanceChange: () -> Unit) {
    val c = LocalSleepColors.current
    val context = LocalContext.current
    var year by remember { mutableIntStateOf(LocalDate.now().year) }
    var metric by remember { mutableStateOf(Prefs.lastMetric(context)) }
    var visibleMetrics by remember { mutableStateOf(Prefs.visibleMetrics(context)) }
    var goals by remember { mutableStateOf(Prefs.goals(context)) }
    var recoveryConfig by remember { mutableStateOf(Prefs.recoveryConfig(context)) }
    var home by remember { mutableStateOf(Prefs.home(context)) }
    var demo by remember { mutableStateOf(false) }
    var tab by remember {
        mutableStateOf(runCatching { Tab.valueOf(Prefs.home(context).startTab) }.getOrDefault(Tab.HOME))
    }
    var refreshKey by remember { mutableIntStateOf(0) }
    var display by remember { mutableStateOf(Prefs.display(context)) }
    var state by remember { mutableStateOf<UiState>(UiState.Loading) }
    val scope = rememberCoroutineScope()

    // Ce que le score de récupération sert quelque part : son onglet, ou la carte d'accueil.
    val recoveryShown = Metric.RECOVERY in visibleMetrics || home.card
    val requested = remember(visibleMetrics, recoveryShown, recoveryConfig) {
        requestedPermissions(visibleMetrics, recoveryShown, recoveryConfig)
    }

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
        val today = LocalDate.now()
        val config = Prefs.recoveryConfig(context)
        // L'année est découpée dans tout l'historique, pas lue seule : les scores de
        // récupération de janvier gardent ainsi leur ligne de base de décembre.
        fun ready(all: HealthData, missing: Set<String>): UiState.Ready {
            val scored = all.withRecovery(config)
            return UiState.Ready(scored.filterYear(year), scored.since(today.minusDays(RECENT_DAYS)), missing)
        }

        if (demo) {
            val all = setOf(year - 1, year, today.year - 1, today.year)
                .map(::demoHealthData)
                .reduce(HealthData::plus)
            state = ready(all, emptySet())
            return@LaunchedEffect
        }
        // Ce que l'app a déjà lu ou importé : de quoi afficher une année même si Health
        // Connect ne la restitue plus, ou pas encore. Le temps d'écran y est versé d'abord :
        // Android n'en garde qu'une dizaine de jours, l'archive fait le reste.
        val archived = withContext(Dispatchers.IO) {
            if (Metric.SCREEN in visibleMetrics) syncScreenTime(context)
            Archive.load(context)
        }
        if (HealthConnectClient.getSdkStatus(context) != HealthConnectClient.SDK_AVAILABLE) {
            state = if (archived.isEmpty()) UiState.NotInstalled else ready(archived, emptySet())
            return@LaunchedEffect
        }
        val client = HealthConnectClient.getOrCreate(context)
        state = try {
            val granted = client.permissionController.getGrantedPermissions()
            if (DATA_PERMISSIONS.keys.none { it in granted }) {
                if (archived.isEmpty()) {
                    UiState.NeedsPermission
                } else {
                    ready(archived, REQUESTED_PERMISSIONS - granted)
                }
            } else {
                // Quelques semaines avant le 1er janvier : la ligne de base de la récupération.
                val fresh = loadHealthData(
                    client, granted,
                    LocalDate.of(year, 1, 1).minusDays(BASELINE_CHOICES.max() + 7L),
                    LocalDate.of(year, 12, 31),
                )
                val all = withContext(Dispatchers.IO) {
                    // Health Connect a le dernier mot sur les jours qu'il connaît ; l'archive
                    // comble le reste.
                    val all = Archive.merge(context, fresh)
                    // Le widget et les rappels lisent ce cache : on le rafraîchit à chaque
                    // passage sur l'année en cours. Le widget n'est redessiné que si son
                    // image peut avoir changé : nouveau contenu, ou nouveau jour.
                    if (year == today.year) {
                        val changed = DataCache.save(context, all)
                        if (changed || !widgetsDrawnToday()) updateAllWidgets(context)
                    }
                    all
                }
                ready(all, REQUESTED_PERMISSIONS - granted)
            }
        } catch (e: Exception) {
            UiState.Error(e.message ?: e.javaClass.simpleName)
        }
    }

    fun selectTab(next: Tab) {
        if (tab == Tab.SETTINGS && next != Tab.SETTINGS) {
            visibleMetrics = Prefs.visibleMetrics(context)
            display = Prefs.display(context)
            goals = Prefs.goals(context)
            recoveryConfig = Prefs.recoveryConfig(context)
            home = Prefs.home(context)
            if (metric !in visibleMetrics) metric = visibleMetrics.first()
            // Dessiné hors du fil de l'interface : le changement d'onglet n'attend pas le
            // rendu des images du widget.
            val app = context.applicationContext
            scope.launch(Dispatchers.IO) { updateAllWidgets(app) }
            // Un import a pu enrichir l'archive : on relit.
            refreshKey++
        }
        tab = next
    }

    val ready = state as? UiState.Ready
    Scaffold(
        containerColor = c.background,
        bottomBar = { if (ready != null) BottomBar(tab, ::selectTab) },
    ) { insets ->
        // Chaque onglet garde sa propre position de défilement.
        val scroll = remember(tab) { ScrollState(0) }
        Box(
            Modifier
                .fillMaxSize()
                .padding(insets)
                .verticalScroll(scroll)
                .padding(aubeOr(16.dp, AubeDimens.ScreenMargin))
        ) {
            when (val s = state) {
                UiState.Loading -> Box(Modifier.fillMaxWidth().padding(top = 120.dp), Alignment.Center) {
                    CircularProgressIndicator(color = c.accent)
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
                    body = "Sleep Track lit tes nuits, tes pas et ta variabilité cardiaque dans " +
                        "Health Connect pour dessiner tes grilles et calculer ta récupération. " +
                        "Rien ne quitte ton téléphone.",
                    action = "Autoriser l'accès",
                    onAction = { permissionLauncher.launch(requested) },
                    onDemo = { demo = true },
                )
                is UiState.Error -> Message(
                    title = "Erreur de lecture",
                    body = s.message,
                    action = "Réessayer",
                    onAction = { refreshKey++ },
                    onDemo = { demo = true },
                )
                is UiState.Ready -> when (tab) {
                    Tab.HOME -> HomeScreen(
                        recent = s.recent,
                        visibleMetrics = visibleMetrics,
                        goals = goals,
                        home = home,
                        missingHrv = PERMISSION_READ_HRV in s.missing && !demo &&
                            RecoveryComponent.HRV in recoveryConfig.components,
                        showNotes = display.notes,
                        demo = demo,
                        onOpenMetric = {
                            metric = it
                            Prefs.setLastMetric(context, it)
                            selectTab(Tab.GRIDS)
                        },
                        onRequestPermissions = { permissionLauncher.launch(requested) },
                        onExitDemo = { demo = false },
                        onPersonalize = { selectTab(Tab.SETTINGS) },
                    )
                    Tab.GRIDS -> MainScreen(
                        year = year,
                        metric = metric,
                        visibleMetrics = visibleMetrics,
                        goals = goals,
                        display = display,
                        data = s.data,
                        missing = s.missing,
                        requested = requested,
                        demo = demo,
                        onYear = { year = it },
                        onMetric = {
                            metric = it
                            Prefs.setLastMetric(context, it)
                        },
                        onRequestPermissions = { permissionLauncher.launch(requested) },
                        onExitDemo = { demo = false },
                    )
                    Tab.SETTINGS -> SettingsScreen(data = s.data, onAppearanceChange = onAppearanceChange)
                }
            }
        }
    }
}

@Composable
private fun BottomBar(current: Tab, onSelect: (Tab) -> Unit) {
    val c = LocalSleepColors.current
    NavigationBar(containerColor = c.tabBar) {
        Tab.entries.forEach { entry ->
            NavigationBarItem(
                selected = entry == current,
                onClick = { onSelect(entry) },
                icon = { Icon(painterResource(entry.icon), contentDescription = null) },
                label = { Text(entry.label) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = c.onAccentContainer,
                    selectedTextColor = c.ink,
                    indicatorColor = c.accentContainer,
                    unselectedIconColor = c.ink2,
                    unselectedTextColor = c.ink2,
                ),
            )
        }
    }
}

@Composable
internal fun DemoBanner(onExitDemo: () -> Unit) {
    val c = LocalSleepColors.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "Mode démo (données fictives)",
            color = c.notice,
            style = sleepText(TextRole.Caption, 13.sp),
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onExitDemo) { Text("Quitter", color = c.ink) }
    }
}

@Composable
private fun MainScreen(
    year: Int,
    metric: Metric,
    visibleMetrics: List<Metric>,
    goals: Goals,
    display: DisplayPrefs,
    data: HealthData,
    missing: Set<String>,
    requested: Set<String>,
    demo: Boolean,
    onYear: (Int) -> Unit,
    onMetric: (Metric) -> Unit,
    onRequestPermissions: () -> Unit,
    onExitDemo: () -> Unit,
) {
    val c = LocalSleepColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selected by remember(year) { mutableStateOf<LocalDate?>(null) }
    val today = LocalDate.now()
    val currentYear = today.year
    val scale = remember(metric, goals, c) { scaleFor(metric, goals, c.levels) }
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

    Column(verticalArrangement = Arrangement.spacedBy(aubeOr(12.dp, AubeDimens.CardGap))) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Grilles", color = c.ink, style = sleepText(TextRole.ScreenTitle, 20.sp, FontWeight.Bold))
            Spacer(Modifier.weight(1f))
            if (display.showShare) {
                Box {
                    var shareMenu by remember { mutableStateOf(false) }
                    // Un seul rendu à la fois : deux images 4K en parallèle, c'est 80 Mo.
                    var sharing by remember { mutableStateOf(false) }
                    TextButton(onClick = { shareMenu = true }) {
                        Text("Partager", color = c.ink2, style = sleepText(TextRole.Label, 14.sp))
                    }
                    DropdownMenu(
                        expanded = shareMenu,
                        onDismissRequest = { shareMenu = false },
                        containerColor = c.surface,
                    ) {
                        ShareQuality.entries.forEach { quality ->
                            DropdownMenuItem(
                                text = { Text(quality.label, color = c.ink) },
                                onClick = {
                                    shareMenu = false
                                    if (!sharing) {
                                        sharing = true
                                        scope.launch {
                                            try {
                                                shareYearImage(context, year, metric, data, quality)
                                            } finally {
                                                sharing = false
                                            }
                                        }
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }

        if (demo) DemoBanner(onExitDemo)

        if (visibleMetrics.size > 1) MetricSwitch(metric, visibleMetrics, onMetric)

        Panel {
            Column(Modifier.padding(aubeOr(16.dp, AubeDimens.CardPadding)), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ArrowButton("‹", enabled = true) { onYear(year - 1) }
                    Text("$year", color = c.ink, style = sleepText(TextRole.CardTitle, 34.sp, FontWeight.Bold))
                    ArrowButton("›", enabled = year < currentYear) { onYear(year + 1) }
                    Spacer(Modifier.weight(1f))
                    Text(metric.countLabel(series.size), color = c.ink2, style = sleepText(TextRole.Caption, 18.sp))
                }
                YearHeatmap(
                    year = year,
                    selected = selected,
                    onSelect = { selected = it },
                    levelAt = { day -> series[day]?.let(scale::levelOf) },
                    minPitch = minPitch,
                )
                if (display.legend) Legend(metric, scale)
            }
        }

        if (display.dayDetail) {
            selected?.let { day -> DayDetail(day, data, visibleMetrics, goals) }
        }

        if (metric == Metric.SCREEN && !demo && !hasUsageAccess(context)) {
            Panel {
                Column(Modifier.padding(aubeOr(16.dp, AubeDimens.CardPadding)), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Le temps d'écran ne vient pas de Health Connect mais d'Android. Autorise " +
                            "Sleep Track dans « Accès aux données d'utilisation », puis reviens ici.",
                        color = c.ink2,
                        style = sleepText(TextRole.Body, 13.sp),
                    )
                    OutlinedButton(onClick = { openUsageAccessSettings(context) }) {
                        Text("Ouvrir le réglage", color = c.ink)
                    }
                    if (display.notes) {
                        Text(
                            "Android n'en garde qu'une dizaine de jours : l'historique commence là, " +
                                "puis s'allonge à chaque ouverture de l'app.",
                            color = c.ink3,
                            style = sleepText(TextRole.Caption, 11.sp),
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
                val tiles = remember(metric, data, scale, today) { statTiles(metric, data, scale, goals, c.ink2) }
                StatRow(tiles[0], tiles[1])
                StatRow(tiles[2], tiles[3])
            }
            if (display.streaks) {
                val streaks = remember(metric, data, goals, year, today, c) {
                    streakTiles(metric, data, goals, year, c.levels, c.ink2, today)
                }
                streaks?.let { (current, best) ->
                    StatRow(current, best)
                    if (display.notes) {
                        targetFor(metric, goals).let { target ->
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

        val relevantMissing = remember(missing, requested) { requested.intersect(missing) }
        if (relevantMissing.isNotEmpty() && !demo) {
            Panel {
                Column(Modifier.padding(aubeOr(16.dp, AubeDimens.CardPadding)), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(missingText(relevantMissing), color = c.ink2, style = sleepText(TextRole.Body, 13.sp))
                    OutlinedButton(onClick = onRequestPermissions) {
                        Text("Compléter les autorisations", color = c.ink)
                    }
                }
            }
        }
    }
}

private fun missingText(missing: Set<String>): String {
    val parts = DATA_PERMISSIONS.filterKeys { it in missing }.values.toList()
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
internal fun MetricSwitch(metric: Metric, entries: List<Metric>, onMetric: (Metric) -> Unit) {
    val c = LocalSleepColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .background(c.surface, RoundedCornerShape(14.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        entries.forEach { entry ->
            val active = entry == metric
            Box(
                Modifier
                    .weight(1f)
                    .background(
                        if (active) c.surface2 else Color.Transparent,
                        RoundedCornerShape(11.dp),
                    )
                    .clickable { onMetric(entry) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    entry.label,
                    color = if (active) c.ink else c.ink2,
                    style = sleepText(
                        TextRole.Label,
                        when {
                            entries.size > 5 -> 11.sp
                            entries.size > 4 -> 12.sp
                            else -> 14.sp
                        },
                        if (active) FontWeight.SemiBold else FontWeight.Normal,
                    ),
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}

@Composable
internal fun ArrowButton(symbol: String, enabled: Boolean, onClick: () -> Unit) {
    val c = LocalSleepColors.current
    TextButton(
        onClick = onClick,
        enabled = enabled,
        contentPadding = PaddingValues(0.dp),
        modifier = Modifier.width(36.dp),
    ) {
        Text(symbol, fontSize = 30.sp, color = if (enabled) c.ink2 else c.surface)
    }
}

/** Les rayons d'Aube : carte principale, section ou grille, tuile. */
internal enum class CardKind(val aubeRadius: Dp) {
    Main(AubeDimens.CardRadius),
    Section(AubeDimens.SectionRadius),
    Tile(AubeDimens.TileRadius),
}

@Composable
internal fun Panel(kind: CardKind = CardKind.Section, content: @Composable () -> Unit) {
    val c = LocalSleepColors.current
    val base = Modifier.fillMaxWidth()
    Box(
        if (c.isAube) base.aubeCard(c, kind.aubeRadius) else base.background(c.surface, RoundedCornerShape(20.dp))
    ) { content() }
}

@Composable
internal fun DayDetail(day: LocalDate, data: HealthData, visibleMetrics: List<Metric>, goals: Goals) {
    val c = LocalSleepColors.current
    Panel(CardKind.Main) {
        Column(Modifier.padding(aubeOr(16.dp, AubeDimens.CardPadding)), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                day.format(LongDate).replaceFirstChar { it.uppercase() },
                color = c.ink,
                style = sleepText(TextRole.CardTitle, classicWeight = FontWeight.SemiBold),
            )
            visibleMetrics.forEach { entry ->
                val value = data.series(entry)[day]
                DetailLine(
                    entry.detailLabel,
                    value?.let(entry::format) ?: "Aucune donnée",
                    value?.let { scaleFor(entry, goals, c.levels).colorOf(it) },
                )
            }
            if (Metric.RECOVERY in visibleMetrics) {
                data.recovery[day]?.let { score ->
                    Text("Détail de la récupération", color = c.ink2, style = sleepText(TextRole.Caption, 12.sp))
                    RecoveryBreakdown(day, score, data, goals)
                }
            }
        }
    }
}

@Composable
internal fun DetailLine(label: String, value: String, color: Color?) {
    val c = LocalSleepColors.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        // En Aube, la pastille du niveau ; en Classique, le petit carré d'avant.
        Box(Modifier.size(14.dp).background(color ?: c.surface2, if (c.isAube) CircleShape else RoundedCornerShape(4.dp)))
        Spacer(Modifier.width(10.dp))
        Text(label, color = c.ink2, modifier = Modifier.weight(1f), style = sleepText(TextRole.Body, 14.sp))
        Text(value, color = color ?: c.ink2, style = sleepText(TextRole.Label, 14.sp, FontWeight.SemiBold))
    }
}

@Composable
private fun StatRow(left: StatTile, right: StatTile) {
    Row(horizontalArrangement = Arrangement.spacedBy(aubeOr(12.dp, AubeDimens.CardGap))) {
        StatTileView(left, Modifier.weight(1f))
        StatTileView(right, Modifier.weight(1f))
    }
}

@Composable
private fun StatTileView(tile: StatTile, modifier: Modifier) {
    val c = LocalSleepColors.current
    val shaped = if (c.isAube) {
        modifier.aubeCard(c, AubeDimens.TileRadius).padding(AubeDimens.TilePadding)
    } else {
        modifier.background(c.surface, RoundedCornerShape(16.dp)).padding(14.dp)
    }
    Box(shaped) {
        Column {
            Text(tile.label, color = c.ink2, style = sleepText(TextRole.Caption, 12.sp))
            Text(tile.value, color = tile.color, style = sleepText(TextRole.Value, 22.sp, FontWeight.Bold), maxLines = 1)
        }
    }
}

@Composable
private fun EmptyNote(text: String) {
    val c = LocalSleepColors.current
    Text(text, color = c.ink2, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center, style = sleepText(TextRole.Body))
}

@Composable
private fun Message(title: String, body: String, action: String, onAction: () -> Unit, onDemo: () -> Unit) {
    val c = LocalSleepColors.current
    Column(
        Modifier.fillMaxWidth().padding(top = 80.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(title, color = c.ink, textAlign = TextAlign.Center, style = sleepText(TextRole.Verdict, 22.sp, FontWeight.Bold))
        Text(body, color = c.ink2, textAlign = TextAlign.Center, style = sleepText(TextRole.Body, 15.sp))
        Button(
            onClick = onAction,
            colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = c.onAccent),
        ) { Text(action) }
        TextButton(onClick = onDemo) { Text("Voir une démo", color = c.ink2) }
    }
}
