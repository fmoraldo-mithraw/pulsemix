package com.pulsemix.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pulsemix.app.PlayerViewModel
import com.pulsemix.app.mix.MixEngine
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Sous-écran « Tonalités » : la roue Camelot de la bibliothèque. Deux
 * anneaux de 12 cases (1A-12A mineur à l'intérieur, 1B-12B majeur à
 * l'extérieur), remplissage proportionnel au nombre de morceaux. Taper une
 * case sélectionne la tonalité : dessous, ses morceaux (tap = jouer) et un
 * mix des morceaux compatibles au sens DJ (même case, ±1, relative — via
 * [MixEngine.camelotScore]).
 */
@Composable
fun CamelotWheelScreen(vm: PlayerViewModel, onBack: () -> Unit) {
    BackHandler { onBack() }
    val tracks by vm.tracks.collectAsStateWithLifecycle()
    var selected by rememberSaveable { mutableStateOf<String?>(null) }

    val byKey = remember(tracks) {
        tracks.filter { it.analyzed && it.camelot != "--" }
            .groupBy { it.camelot }
    }
    val counts = remember(byKey) { byKey.mapValues { it.value.size } }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        SubHeader("Tonalités", onBack)
        Text(
            "Touche une case : intérieur = mineur (A), extérieur = majeur (B). " +
                "Plus la case est colorée, plus la tonalité est fournie.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
        Spacer(Modifier.height(8.dp))

        CamelotWheel(
            counts = counts,
            selected = selected,
            onSelect = { key -> selected = if (selected == key) null else key },
            modifier = Modifier
                .fillMaxWidth(0.82f)
                .align(Alignment.CenterHorizontally)
                .aspectRatio(1f)
        )

        val key = selected
        if (key == null) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Aucune tonalité sélectionnée.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        } else {
            // Triés par BPM : c'est l'ordre naturel d'un mix harmonique
            val inKey = remember(byKey, key) {
                byKey[key].orEmpty().sortedBy { it.bpm }
            }
            val compatible = remember(tracks, key) {
                tracks.filter {
                    it.analyzed &&
                        MixEngine.camelotScore(key, it.camelot) >= 0.8f
                }.sortedBy { it.bpm }
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "$key : ${inKey.size} morceaux, " +
                        "${compatible.size} compatibles (±1, relative)",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.weight(1f)
                )
            }
            OutlinedButton(
                onClick = { vm.playTracks(compatible) },
                enabled = compatible.size >= 2
            ) { Text("▶ Mix dans cette tonalité (${compatible.size})") }
            Spacer(Modifier.height(4.dp))
            LazyColumn(Modifier.weight(1f)) {
                items(inKey, key = { it.uri }) { t ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clickable { vm.playTrack(t) }
                            .padding(vertical = 6.dp)
                    ) {
                        Text(
                            t.title,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            buildString {
                                if (t.artist.isNotBlank()) {
                                    append(t.artist).append(" · ")
                                }
                                append("${t.bpm} BPM · ${t.keyName}")
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface
                                .copy(alpha = 0.6f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.surfaceVariant
                    )
                }
            }
        }
    }
}

/**
 * La roue elle-même : 24 arcs dessinés au Canvas, libellés via le canvas
 * natif (Compose n'a pas de drawText simple). Disposée comme une horloge :
 * 12 en haut, sens horaire — la présentation habituelle de la roue Camelot.
 */
@Composable
private fun CamelotWheel(
    counts: Map<String, Int>,
    selected: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val primary = MaterialTheme.colorScheme.primary
    val base = MaterialTheme.colorScheme.surfaceVariant
    val labelColor = MaterialTheme.colorScheme.onSurface
    val maxCount = (counts.values.maxOrNull() ?: 0).coerceAtLeast(1)
    // Paint natif mémorisé : un objet Android, pas un état Compose
    val labelPaint = remember(labelColor) {
        android.graphics.Paint().apply {
            isAntiAlias = true
            color = labelColor.toArgb()
            textAlign = android.graphics.Paint.Align.CENTER
            isFakeBoldText = true
        }
    }

    Canvas(
        modifier.pointerInput(counts) {
            detectTapGestures { pos ->
                val cx = size.width / 2f
                val cy = size.height / 2f
                val dx = pos.x - cx
                val dy = pos.y - cy
                val dist = sqrt(dx * dx + dy * dy)
                // Mêmes proportions que le dessin ci-dessous
                val rOut = minOf(cx, cy)
                val rMid = rOut * 0.66f
                val rIn = rOut * 0.32f
                val ring = when {
                    dist in rIn..rMid -> "A"
                    dist > rMid && dist <= rOut -> "B"
                    else -> return@detectTapGestures
                }
                // Angle horaire depuis le haut : 12 à midi, 1..11 en tournant
                val deg = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble()))
                val m = ((((deg + 90 + 15) % 360 + 360) % 360) / 30).toInt() % 12
                val n = if (m == 0) 12 else m
                onSelect("$n$ring")
            }
        }
    ) {
        val rOut = size.minDimension / 2f
        val rMid = rOut * 0.66f
        val rIn = rOut * 0.32f
        val center = Offset(size.width / 2f, size.height / 2f)
        val rings = listOf("A" to (rIn to rMid), "B" to (rMid to rOut))
        for (n in 1..12) {
            // Case centrée sur -90° + n×30° (horloge), 28° de large : les 2°
            // restants dessinent la séparation entre cases.
            val centerAngle = -90f + (n % 12) * 30f
            for ((ring, radii) in rings) {
                val (r0, r1) = radii
                val keyName = "$n$ring"
                val count = counts[keyName] ?: 0
                val color: Color = when {
                    keyName == selected -> primary
                    count == 0 -> base.copy(alpha = 0.35f)
                    else -> primary.copy(
                        alpha = 0.15f + 0.5f * count / maxCount
                    )
                }
                val midR = (r0 + r1) / 2f
                drawArc(
                    color = color,
                    startAngle = centerAngle - 14f,
                    sweepAngle = 28f,
                    useCenter = false,
                    topLeft = Offset(center.x - midR, center.y - midR),
                    size = Size(midR * 2f, midR * 2f),
                    style = Stroke(width = (r1 - r0) * 0.86f)
                )
                // Libellé au centre de la case, via le canvas natif
                labelPaint.textSize = rOut * 0.085f
                val rad = Math.toRadians(centerAngle.toDouble())
                drawContext.canvas.nativeCanvas.drawText(
                    keyName,
                    center.x + midR * cos(rad).toFloat(),
                    center.y + midR * sin(rad).toFloat() +
                        labelPaint.textSize / 3f,
                    labelPaint
                )
            }
        }
    }
}
