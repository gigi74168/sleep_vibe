package com.paul.sleeptrack

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/** Questions proposées tant que la discussion est vide : de quoi voir ce qu'on peut demander. */
private val SUGGESTIONS = listOf(
    "Comment je pourrais mieux dormir ?",
    "Mon temps d'écran joue-t-il sur mes nuits ?",
    "Comment s'est passée ma semaine ?",
    "Quel est mon meilleur jour de la semaine ?",
)

/**
 * Discuter avec ses données. Tout tourne sur le téléphone, avec Gemini Nano : les chiffres
 * sont calculés par l'app ([chatFacts]) et le modèle ne fait que rédiger à partir d'eux.
 *
 * Contrairement aux commentaires de l'écran principal, cet écran ne disparaît pas sur un
 * téléphone que le modèle ne couvre pas : il a été demandé explicitement, il doit donc dire
 * pourquoi il ne répond pas plutôt que de laisser croire à une panne.
 *
 * La conversation vit le temps de l'écran : rien n'est écrit sur le disque.
 */
@Composable
fun ChatScreen(
    data: HealthData,
    visible: List<Metric>,
    goalMinutes: Int,
    year: Int,
    demo: Boolean,
    pageScroll: ScrollState,
    onBack: () -> Unit,
) {
    val nano = rememberNano()
    val messages = remember { mutableStateListOf<ChatMessage>() }
    var input by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // Recalculés seulement quand les données changent, pas à chaque message.
    val facts = remember(data, visible, goalMinutes, year) { chatFacts(data, visible, goalMinutes, year) }

    fun send(text: String) {
        val question = text.trim()
        if (question.isEmpty() || running) return
        val history = messages.toList()
        messages += ChatMessage(fromUser = true, text = question)
        input = ""
        running = true
        scope.launch {
            val answer = nano.nano.chat(facts, history, question)
            messages += ChatMessage(
                fromUser = false,
                text = answer ?: "Je n'ai pas réussi à répondre cette fois. Réessaie dans un instant, " +
                    "ou reformule la question plus simplement.",
            )
            running = false
        }
    }

    // La page entière défile : on la ramène en bas à chaque nouveau message, une fois la
    // bulle mise en page (d'où l'attente d'une image).
    LaunchedEffect(messages.size, running) {
        withFrameNanos { }
        pageScroll.animateScrollTo(pageScroll.maxValue)
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Discuter", color = Palette.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            if (messages.isNotEmpty() && !running) {
                TextButton(onClick = { messages.clear() }) {
                    Text("Effacer", color = Palette.muted, fontSize = 14.sp)
                }
            }
            TextButton(onClick = onBack) { Text("Retour", color = Palette.muted, fontSize = 14.sp) }
        }

        when {
            !nano.checked -> Box(Modifier.fillMaxWidth().padding(top = 40.dp), Alignment.Center) {
                CircularProgressIndicator(color = Palette.levels.last())
            }
            nano.status == NanoStatus.UNSUPPORTED -> ChatUnavailable()
            !nano.ready -> ChatPanel { NanoDownloadPanel(nano) }
            else -> {
                if (messages.isEmpty()) {
                    ChatIntro(demo) { send(it) }
                }
                messages.forEach { Bubble(it) }
                if (running) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(14.dp), color = Palette.muted, strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("Rédaction sur le téléphone…", color = Palette.muted, fontSize = 13.sp)
                    }
                }
                ChatInput(
                    value = input,
                    enabled = !running,
                    onChange = { input = it },
                    onSend = { send(input) },
                )
                Text(
                    "Rédigé sur ton téléphone par un petit modèle de langage, à partir des chiffres " +
                        "calculés par l'app. Il peut se tromper, et ce ne sont pas des conseils " +
                        "médicaux. Rien ne quitte le téléphone et la conversation n'est pas gardée.",
                    color = Palette.muted.copy(alpha = 0.7f),
                    fontSize = 11.sp,
                )
            }
        }
    }
}

@Composable
private fun ChatIntro(demo: Boolean, onPick: (String) -> Unit) {
    ChatPanel {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                "Pose une question sur tes nuits, tes pas, ton cœur, ton poids ou ton temps " +
                    "d'écran. Je peux aussi te proposer des pistes, à partir de tes chiffres.",
                color = Palette.muted,
                fontSize = 14.sp,
            )
            if (demo) {
                Text("Mode démo : les réponses portent sur des données fictives.", color = Palette.levels[2], fontSize = 12.sp)
            }
            SUGGESTIONS.forEach { suggestion ->
                Text(
                    suggestion,
                    color = Palette.text,
                    fontSize = 14.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Palette.empty, RoundedCornerShape(12.dp))
                        .clickable { onPick(suggestion) }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
        }
    }
}

@Composable
private fun Bubble(message: ChatMessage) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (message.fromUser) Arrangement.End else Arrangement.Start) {
        Text(
            message.text,
            color = if (message.fromUser) Palette.text else Palette.text.copy(alpha = 0.9f),
            fontSize = 15.sp,
            modifier = Modifier
                .fillMaxWidth(if (message.fromUser) 0.85f else 0.95f)
                .wrapContentWidth(if (message.fromUser) Alignment.End else Alignment.Start)
                .background(
                    if (message.fromUser) Palette.empty else Palette.card,
                    RoundedCornerShape(16.dp),
                )
                .padding(horizontal = 14.dp, vertical = 10.dp),
        )
    }
}

@Composable
private fun ChatInput(value: String, enabled: Boolean, onChange: (String) -> Unit, onSend: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            modifier = Modifier.weight(1f),
            placeholder = { Text("Ta question", color = Palette.muted, fontSize = 14.sp) },
            enabled = enabled,
            maxLines = 4,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { onSend() }),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Palette.text,
                unfocusedTextColor = Palette.text,
                focusedBorderColor = Palette.levels.last(),
                unfocusedBorderColor = Palette.empty,
                cursorColor = Palette.levels.last(),
            ),
        )
        TextButton(onClick = onSend, enabled = enabled && value.isNotBlank()) {
            Text("Envoyer", color = if (enabled && value.isNotBlank()) Palette.levels.last() else Palette.muted)
        }
    }
}

@Composable
private fun ChatUnavailable() {
    ChatPanel {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Pas disponible sur ce téléphone", color = Palette.text, fontWeight = FontWeight.SemiBold)
            Text(
                "La discussion tourne entièrement sur le téléphone, avec Gemini Nano. Android ne " +
                    "le propose que sur une liste d'appareils récents, et jamais quand le bootloader " +
                    "est déverrouillé. Ce téléphone n'en fait pas partie, ou le service système " +
                    "AICore n'est pas encore prêt : une mise à jour d'Android peut suffire.",
                color = Palette.muted,
                fontSize = 13.sp,
            )
            Text(
                "Pour ne plus voir l'entrée « Discuter » : Réglages › Affichage › Commentaires du modèle local.",
                color = Palette.muted.copy(alpha = 0.7f),
                fontSize = 11.sp,
            )
        }
    }
}

@Composable
private fun ChatPanel(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxWidth().background(Palette.card, RoundedCornerShape(20.dp))) { content() }
}
