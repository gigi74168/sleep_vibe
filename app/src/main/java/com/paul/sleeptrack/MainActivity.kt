package com.paul.sleeptrack

import android.Manifest
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
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
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.time.Duration
import java.time.LocalDate

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Un arrêt forcé annule les alarmes : on les repose à chaque lancement.
        Reminders.reschedule(this)
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
    data class Ready(val data: HealthData, val missing: Set<String>, val warning: String? = null) : UiState
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
    // Années déjà lues en direct dans Health Connect depuis le lancement de l'app :
    // sert à éviter de retaper toute une année à chaque retour au premier plan.
    val fetchedYears = remember { mutableStateOf(setOf<Int>()) }

    val permissionLauncher = rememberLauncherForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { refreshKey++ }

    // Retour (bouton ou geste) depuis les réglages : revient à l'écran principal au lieu
    // d'éteindre l'app, en appliquant les mêmes effets que le bouton "Retour".
    val closeSettings: () -> Unit = {
        settings = false
        visibleMetrics = Prefs.visibleMetrics(context)
        display = Prefs.display(context)
        if (metric !in visibleMetrics) metric = Metric.SLEEP
        updateAllWidgets(context)
        refreshKey++
    }
    BackHandler(enabled = settings) { closeSettings() }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refreshKey++ }

    LaunchedEffect(year, demo, refreshKey) {
        if (demo) {
            state = UiState.Ready(demoHealthData(year), emptySet())
            return@LaunchedEffect
        }
        // Ce que l'app a déjà lu ou importé : de quoi afficher une année même si Health
        // Connect ne la restitue plus, ou pas encore. Le temps d'écran y est versé d'abord :
        // Android n'en garde qu'une dizaine de jours, l'archive fait le reste.
        val archived = withContext(Dispatchers.IO) {
            if (Metric.SCREEN in visibleMetrics) {
                Archive.merge(context, HealthData(screen = readRecentScreenTime(context)))
            }
            Archive.load(context).filterYear(year)
        }
        if (HealthConnectClient.getSdkStatus(context) != HealthConnectClient.SDK_AVAILABLE) {
            state = if (archived.isEmpty()) UiState.NotInstalled else UiState.Ready(archived, emptySet())
            return@LaunchedEffect
        }
        val client = HealthConnectClient.getOrCreate(context)
        try {
            // Un appel Health Connect (y compris la simple vérif des permissions) peut
            // rester bloqué sans jamais répondre plutôt que de renvoyer une erreur —
            // ça s'est vu. Cet appel-là est local et léger, 15s est largement assez.
            val granted = withTimeout(15_000) { client.permissionController.getGrantedPermissions() }
            Log.i("SleepTrack-HC", "Permissions Health Connect accordées : ${granted.sorted()}")
            if (DATA_PERMISSIONS.values.none { it in granted }) {
                state = if (archived.isEmpty()) {
                    UiState.NeedsPermission
                } else {
                    UiState.Ready(archived, REQUESTED_PERMISSIONS - granted)
                }
                return@LaunchedEffect
            }
            val today = LocalDate.now()
            val alreadyFetched = year in fetchedYears.value
            when {
                // Année passée déjà lue une fois cette session : ses données ne
                // bougent plus, inutile de retaper Health Connect à chaque retour au
                // premier plan — c'est ça qui faisait taper le rate limit en boucle.
                // On se contente de l'archive.
                alreadyFetched && year != today.year -> {
                    state = UiState.Ready(archived, REQUESTED_PERMISSIONS - granted)
                }
                // Année en cours déjà lue une fois : on ne relit que les derniers
                // jours (seuls susceptibles d'avoir changé), pas toute l'année. La
                // table sommeil peut être énorme (des milliers de fragments écrits par
                // une app de synchro) : on borne chaque sous-lecture pour ne jamais
                // bloquer le lancement là-dessus.
                alreadyFetched -> {
                    val aggregatePart = try {
                        withTimeout(20_000) { loadAggregated(client, granted, today.minusDays(3), today) }
                    } catch (e: TimeoutCancellationException) {
                        PartialResult(HealthData(), rateLimited = true)
                    }
                    val sleepPart = if (PERMISSION_READ_SLEEP in granted) {
                        try {
                            withTimeout(10_000) { readSleepByNight(client, today.minusDays(3), today) }
                        } catch (e: TimeoutCancellationException) {
                            PartialResult<Map<LocalDate, Duration>>(emptyMap(), rateLimited = true)
                        } catch (e: Exception) {
                            Log.w("SleepTrack-HC", "Échec sommeil récent : ${e.message}", e)
                            PartialResult<Map<LocalDate, Duration>>(emptyMap(), rateLimited = true)
                        }
                    } else {
                        PartialResult<Map<LocalDate, Duration>>(emptyMap(), rateLimited = false)
                    }
                    val fresh = HealthLoadResult(
                        data = aggregatePart.data + HealthData(nights = sleepPart.data),
                        rateLimited = aggregatePart.rateLimited || sleepPart.rateLimited,
                    )
                    val data = archived + fresh.data
                    withContext(Dispatchers.IO) { Archive.merge(context, data) }
                    if (year == today.year) {
                        DataCache.save(context, data)
                        updateAllWidgets(context)
                    }
                    val warning = if (fresh.rateLimited) {
                        "Health Connect a limité ou ralenti certaines requêtes : des données récentes n'ont peut-être pas pu être lues."
                    } else {
                        null
                    }
                    state = UiState.Ready(data, REQUESTED_PERMISSIONS - granted, warning)
                }
                // Premier passage sur cette année cette session : on lit d'abord les
                // métriques agrégées (pas, cœur, poids) sur toute l'année en quelques
                // appels — c'est rapide et affiche tout de suite la moitié de l'écran.
                // Puis on comble le sommeil semaine par semaine, la plus récente en
                // premier (affichage rapide), chaque semaine lue venant enrichir
                // l'écran et l'archive au fur et à mesure — plutôt que de faire
                // attendre l'écran sur l'année entière (ou même un mois) d'un coup.
                else -> {
                    var accumulated = archived
                    var anyIssue = false
                    fun warning() = if (anyIssue) {
                        "Health Connect a limité ou ralenti certaines requêtes : des périodes plus anciennes n'ont peut-être pas encore été chargées. Réessaie plus tard pour compléter."
                    } else {
                        null
                    }

                    val baseResult = try {
                        withTimeout(60_000) {
                            loadAggregated(
                                client, granted,
                                LocalDate.of(year, 1, 1),
                                minOf(LocalDate.of(year, 12, 31), today),
                            )
                        }
                    } catch (e: TimeoutCancellationException) {
                        PartialResult(HealthData(), rateLimited = true)
                    }
                    anyIssue = baseResult.rateLimited
                    accumulated += baseResult.data
                    withContext(Dispatchers.IO) { Archive.merge(context, accumulated) }
                    state = UiState.Ready(accumulated, REQUESTED_PERMISSIONS - granted, warning())

                    var consecutiveTimeouts = 0
                    for ((from, to) in weekChunks(year)) {
                        if (PERMISSION_READ_SLEEP !in granted) {
                            Log.w("SleepTrack-HC", "Permission sommeil non accordée : lectures nocturnes sautées")
                            break
                        }
                        val result = try {
                            withTimeout(120_000) { readSleepByNight(client, from, to) }
                        } catch (e: TimeoutCancellationException) {
                            Log.w("SleepTrack-HC", "Timeout sommeil $from..$to")
                            PartialResult<Map<LocalDate, Duration>>(emptyMap(), rateLimited = true)
                        } catch (e: Exception) {
                            // Une lecture qui coince ne doit pas faire planter tout le
                            // chargement : on loggue, on marque le partiel et on passe.
                            Log.w("SleepTrack-HC", "Échec lecture sommeil $from..$to : ${e.message}", e)
                            PartialResult<Map<LocalDate, Duration>>(emptyMap(), rateLimited = true)
                        }
                        Log.i("SleepTrack-HC", "Sommeil $from..$to : ${result.data.size} nuits, rateLimited=${result.rateLimited}")
                        anyIssue = anyIssue || result.rateLimited
                        accumulated = accumulated + HealthData(nights = result.data)
                        withContext(Dispatchers.IO) { Archive.merge(context, accumulated) }
                        if (year == today.year) {
                            DataCache.save(context, accumulated)
                            updateAllWidgets(context)
                        }
                        state = UiState.Ready(accumulated, REQUESTED_PERMISSIONS - granted, warning())
                        // Inutile de continuer à taper Health Connect si le rate
                        // limiter a déjà dit stop : on garde ce qui est lu et on laisse
                        // l'utilisateur relancer à la prochaine ouverture.
                        if (result.rateLimited) {
                            consecutiveTimeouts =
                                if (result.data.isEmpty()) consecutiveTimeouts + 1 else 0
                            // Un timeout ponctuel n'est pas une raison de tout arrêter :
                            // on continue vers des semaines plus anciennes (elles peuvent
                            // être rapides). Mais si ça rame trois semaines de suite, on
                            // abandonne pour ne pas laisser l'écran planter indéfiniment.
                            if (consecutiveTimeouts >= 3) {
                                Log.w("SleepTrack-HC", "Trop de timeouts sommeil consécutifs, on s'arrête")
                                break
                            }
                            continue
                        }
                    }
                    fetchedYears.value = fetchedYears.value + year
                }
            }
        } catch (e: CancellationException) {
            // L'effet a été annulé (changement d'année/écran pendant le chargement) :
            // on ne doit surtout pas continuer à écrire dans `state` après coup, sous
            // peine de "The coroutine scope left the composition". Une CancellationException
            // ici est forcément une vraie annulation Compose : chaque appel Health
            // Connect a désormais son propre withTimeout local, donc plus de
            // TimeoutCancellationException à distinguer à ce niveau.
            throw e
        } catch (e: Exception) {
            // Un vrai imprévu (le rate limit / timeout ne devraient plus arriver
            // jusqu'ici : chaque lecture Health Connect les gère déjà en interne, page
            // par page ou mois par mois, et renvoie du partiel plutôt que de jeter).
            // S'il y a quand même quelque chose en archive, on préfère l'afficher avec
            // un avertissement plutôt que de bloquer tout l'écran.
            val isRateLimit = e.message?.contains("rate limit", ignoreCase = true) == true ||
                e.message?.contains("quota", ignoreCase = true) == true
            val message = if (isRateLimit) {
                "Health Connect a limité les requêtes plus longtemps que prévu. Attends un peu avant de réessayer."
            } else {
                e.message ?: e.javaClass.simpleName
            }
            state = if (archived.isEmpty()) {
                UiState.Error(message)
            } else {
                // On ne connaît pas forcément `granted` ici si le souci vient de la
                // vérif de permission elle-même : REQUESTED_PERMISSIONS par défaut,
                // c'est juste utilisé pour un badge, pas critique.
                UiState.Ready(archived, REQUESTED_PERMISSIONS, warning = message)
            }
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
                onBack = closeSettings,
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
                    warning = if (Prefs.hideSyncErrors(context)) null else s.warning,
                    onRetry = { refreshKey++ },
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
    warning: String? = null,
    onRetry: () -> Unit = {},
    onYear: (Int) -> Unit,
    onMetric: (Metric) -> Unit,
    onRequestPermissions: () -> Unit,
    onExitDemo: () -> Unit,
    onSettings: () -> Unit,
) {
    val context = LocalContext.current
    var selected by remember(year) { mutableStateOf<LocalDate?>(null) }
    // La période mise en avant par une question posée à la grille.
    var asked by remember { mutableStateOf<GridQuery?>(null) }
    // Le surlignage ne vaut que pour la métrique et l'année qu'il décrit. En le dérivant
    // plutôt qu'en l'effaçant, changer d'onglet ou d'année à la main le fait disparaître
    // tout seul — et la réponse à une question survit au changement qu'elle provoque.
    val highlight = asked?.takeIf { it.metric == metric && year in it.from.year..it.to.year }
    val nano = rememberNano()
    val currentYear = LocalDate.now().year
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
            TextButton(onClick = { shareYearImage(context, year, metric, data) }) {
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

        if (warning != null) {
            // Rate limit Health Connect ou autre pépin en cours de lecture : on le
            // signale sans bloquer l'écran, les données déjà là (archive + ce qui a
            // pu être lu) restent affichées en dessous.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(warning, color = Palette.levels[2], fontSize = 13.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = onRetry) { Text("Réessayer", color = Palette.text) }
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
                    highlight = highlight?.range,
                )
                highlight?.let { query ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            highlightLabel(query),
                            color = Palette.text,
                            fontSize = 13.sp,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { asked = null }) {
                            Text("Tout revoir", color = Palette.muted, fontSize = 13.sp)
                        }
                    }
                }
                if (display.legend) Legend(metric, scale)
            }
        }

        selected?.let { day -> DayDetail(day, data, visibleMetrics) }

        // Gemini Nano, en local. Tout ce bloc disparaît sur un appareil que le Prompt API
        // ne couvre pas : l'app redevient alors exactement celle d'avant.
        if (display.ai && nano.ready) {
            Panel {
                NanoAsk(nano, availableYears()) { query ->
                    // Si la métrique demandée est masquée dans les réglages, on garde
                    // l'onglet ouvert : la période reste celle qu'on a demandée, et le
                    // surlignage s'applique quand même au lieu de ne rien faire.
                    val target = if (query.metric in visibleMetrics) query.metric else metric
                    if (target != metric) onMetric(target)
                    if (query.from.year != year) onYear(query.from.year)
                    asked = query.copy(metric = target)
                    selected = null
                }
            }
        }

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
            if (display.stats) {
                val tiles = statTiles(metric, data, scale)
                StatRow(tiles[0], tiles[1])
                StatRow(tiles[2], tiles[3])
            }
            if (display.streaks) {
                streakTiles(metric, data, display.goalMinutes, year)?.let { (current, best) ->
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

        // Le résumé du dimanche est rédigé ici, pendant que l'app est au premier plan :
        // AICore refuse l'inférence depuis un BroadcastReceiver, donc la notification ne
        // saura que relire ce qui aura été préparé. On ne le prépare que depuis l'année en
        // cours, seule à contenir les sept derniers jours.
        LaunchedEffect(nano.ready, data, year) {
            if (!display.ai || !nano.ready || demo) return@LaunchedEffect
            if (year != currentYear || !Prefs.weeklyEnabled(context)) return@LaunchedEffect
            if (!WeeklyBrief.isStale(context)) return@LaunchedEffect
            nano.weeklyBrief(data)?.let { WeeklyBrief.store(context, it) }
        }

        if (display.ai) {
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

// `runCatching` attrape aussi CancellationException, ce qui casse l'annulation structurée :
// si l'écran est quitté pendant un export/import, la coroutine annulée continuerait alors à
// écrire dans un état Compose déjà sorti de la composition ("The coroutine scope left the
// composition"). Ce remplacement relance l'annulation au lieu de l'avaler.
private inline fun <T> catchingExceptCancellation(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }

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
    var hideSyncErrors by remember { mutableStateOf(Prefs.hideSyncErrors(context)) }
    var backupStatus by remember { mutableStateOf<String?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val nano = rememberNano()
    var briefReady by remember { mutableStateOf(WeeklyBrief.load(context) != null) }
    var briefRunning by remember { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            backupStatus = catchingExceptCancellation {
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
            backupStatus = catchingExceptCancellation {
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
            backupStatus = catchingExceptCancellation {
                withContext(Dispatchers.IO) {
                    val imported = importBackup(context, uri)
                    Archive.merge(context, imported)
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
        val message = Reminders.weeklyMessage(context, data)
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

                // Le résumé rédigé par Gemini Nano ne peut pas être produit au moment de
                // notifier : AICore refuse l'inférence en arrière-plan. Il se prépare donc
                // ici, app ouverte, et la notification le relit dimanche.
                if (weekly && nano.ready) {
                    Text(
                        if (briefReady) {
                            "Un résumé rédigé par le modèle local est prêt. Il se périme au " +
                                "bout de deux jours : rouvrir l'app avant dimanche le refait."
                        } else {
                            "Le résumé du dimanche sera gabarité, faute de texte préparé. " +
                                "Le modèle local ne peut pas rédiger pendant que l'app est fermée."
                        },
                        color = Palette.muted,
                        fontSize = 12.sp,
                    )
                    TextButton(
                        onClick = {
                            briefRunning = true
                            scope.launch {
                                val text = nano.weeklyBrief(data)
                                if (text != null) WeeklyBrief.store(context, text)
                                briefReady = text != null
                                briefRunning = false
                            }
                        },
                        enabled = !briefRunning,
                    ) {
                        Text(
                            if (briefRunning) "Rédaction…" else "Préparer le résumé maintenant",
                            color = Palette.muted,
                            fontSize = 13.sp,
                        )
                    }
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
                    title = "Masquer les messages d'erreur de synchronisation",
                    subtitle = "Cache les bandeaux « Health Connect a limité… » lors des lectures",
                    checked = hideSyncErrors,
                ) { on ->
                    Prefs.setFlag(context, Prefs.HIDE_SYNC_ERRORS, on)
                    hideSyncErrors = on
                }
                SettingSwitch(
                    title = "Commentaires du modèle local",
                    subtitle = if (nano.ready) {
                        "Deux phrases rédigées sur le téléphone, et la barre de question"
                    } else {
                        "Ce téléphone ne fait pas tourner le modèle : sans effet ici"
                    },
                    checked = display.ai,
                ) { on ->
                    Prefs.setFlag(context, Prefs.SHOW_AI, on)
                    display = Prefs.display(context)
                    if (!on) WeeklyBrief.clear(context)
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
