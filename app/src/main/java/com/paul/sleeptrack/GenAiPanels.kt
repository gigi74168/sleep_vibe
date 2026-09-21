package com.paul.sleeptrack

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/**
 * Le panneau qui parle à Gemini Nano, et l'état qui l'alimente.
 *
 * Tout part de [NanoStatus] : tant qu'il ne vaut pas [NanoStatus.READY], le commentaire ne
 * s'affiche pas. Sur un appareil non supporté, l'app est exactement celle d'avant,
 * sans bouton grisé ni message d'erreur — le Prompt API ne couvre pas tous les téléphones,
 * ce n'est pas une panne qu'il faut annoncer.
 */
class NanoState(val nano: Nano) {
    var status by mutableStateOf(NanoStatus.UNSUPPORTED)
    var downloaded by mutableStateOf(0L)
    var expected by mutableStateOf(0L)

    val ready: Boolean get() = status == NanoStatus.READY
}

/**
 * Crée le client, interroge AICore une fois, et le referme quand l'écran disparaît.
 * Le `close()` compte : le client tient des ressources natives.
 */
@Composable
fun rememberNano(): NanoState {
    val state = remember { NanoState(Nano()) }
    DisposableEffect(Unit) {
        onDispose { state.nano.close() }
    }
    LaunchedEffect(Unit) {
        state.status = state.nano.status()
    }
    return state
}

// --------------------------------------------------------------- Téléchargement

/**
 * Proposé une seule fois, quand Nano est supporté mais pas encore là. Le téléchargement
 * pèse plusieurs centaines de Mo : il se demande, il ne se déclenche pas tout seul.
 */
@Composable
fun NanoDownloadPanel(state: NanoState) {
    if (state.status != NanoStatus.DOWNLOADABLE && state.status != NanoStatus.DOWNLOADING) return
    val scope = rememberCoroutineScope()

    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Commentaires hors ligne", color = Palette.text, fontWeight = FontWeight.SemiBold)
        Text(
            "Ton téléphone sait faire tourner un modèle de langage en local. Le " +
                "télécharger (plusieurs centaines de Mo, une seule fois) permet à l'app de " +
                "rédiger ses commentaires sous tes statistiques. Tes données de " +
                "santé, elles, ne quittent toujours pas le téléphone.",
            color = Palette.muted,
            fontSize = 13.sp,
        )
        if (state.status == NanoStatus.DOWNLOADING) {
            if (state.expected > 0) {
                LinearProgressIndicator(
                    progress = { (state.downloaded.toFloat() / state.expected).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                    color = Palette.levels.last(),
                    trackColor = Palette.empty,
                )
                Text(
                    "%d / %d Mo".format(state.downloaded / 1_048_576, state.expected / 1_048_576),
                    color = Palette.muted,
                    fontSize = 12.sp,
                )
            } else {
                Text("Téléchargement en cours…", color = Palette.muted, fontSize = 13.sp)
            }
        } else {
            TextButton(onClick = {
                state.status = NanoStatus.DOWNLOADING
                scope.launch {
                    val ok = state.nano.download { received, total ->
                        state.downloaded = received
                        state.expected = total
                    }
                    // On redemande son avis à AICore plutôt que de supposer : un
                    // téléchargement interrompu par le réseau ne rend pas le modèle prêt.
                    state.status = if (ok) state.nano.status() else NanoStatus.DOWNLOADABLE
                }
            }) {
                Text("Télécharger le modèle", color = Palette.levels.last())
            }
        }
    }
}

// ------------------------------------------------------------------ Commentaire

/**
 * Deux phrases sur la métrique affichée. Relancé quand la métrique, l'année ou les données
 * changent ; `null` tant que l'inférence tourne ou si elle a échoué, auquel cas le panneau
 * disparaît sans bruit — les phrases calculées de [WeekProfile] et [CorrelationPanel]
 * restent là de toute façon.
 */
@Composable
fun NanoComment(state: NanoState, metric: Metric, data: HealthData, goalMinutes: Int, year: Int) {
    var text by remember { mutableStateOf<String?>(null) }
    var running by remember { mutableStateOf(false) }

    LaunchedEffect(state.ready, metric, year, data) {
        if (!state.ready) return@LaunchedEffect
        // En dessous d'une poignée de jours, il n'y a rien à croiser.
        if (data.series(metric).size < 14) {
            text = null
            return@LaunchedEffect
        }
        running = true
        text = state.nano.comment(metric, data, goalMinutes, year)
        running = false
    }

    val body = text
    if (!running && body == null) return

    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Ce qui ressort", color = Palette.text, fontWeight = FontWeight.SemiBold)
            if (running) {
                Spacer(Modifier.width(10.dp))
                CircularProgressIndicator(Modifier.size(14.dp), color = Palette.muted, strokeWidth = 2.dp)
            }
        }
        if (body != null) {
            Text(body, color = Palette.muted, fontSize = 14.sp)
            Text(
                "Rédigé sur ton téléphone à partir des chiffres ci-dessus, sans réseau.",
                color = Palette.muted.copy(alpha = 0.7f),
                fontSize = 11.sp,
            )
        }
    }
}
