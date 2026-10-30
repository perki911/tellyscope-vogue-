// SPDX-License-Identifier: GPL-3.0-only

package app.tellyscope.ui

import android.media.AudioManager
import android.media.ToneGenerator
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import app.tellyscope.adb.FeReading
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * A satellite finder: the TV's own tuner readings on the phone, with a beep that speeds up as the
 * signal improves. Hold the phone at the dish and move the dish slowly.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FinderScreen(readings: Flow<FeReading>, back: () -> Unit) {
    var smooth by remember { mutableFloatStateOf(0f) }
    var snr by remember { mutableIntStateOf(0) }
    var locked by remember { mutableStateOf(false) }
    var peak by remember { mutableIntStateOf(0) }
    var seen by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    var sound by rememberSaveable { mutableStateOf(true) }

    LaunchedEffect(readings) {
        try {
            readings.collect { r ->
                if (!r.supported) {
                    problem = "المستقبل رفض قراءة قيم الموالف"
                    return@collect
                }
                problem = null
                // The raw strength jumps by ±15 without a lock; a slow average makes it readable.
                smooth = if (!seen) r.strength.toFloat() else smooth + 0.15f * (r.strength - smooth)
                snr = r.snr
                locked = r.locked
                seen = true
                peak = max(peak, smooth.roundToInt())
            }
            if (!seen) problem = "لم تصل أي قراءة من المستقبل"
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            problem = e.message?.takeIf { it.isNotBlank() } ?: "انقطع الاتصال بالمستقبل"
        }
    }

    val tone = remember { runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 80) }.getOrNull() }
    DisposableEffect(tone) { onDispose { tone?.release() } }
    LaunchedEffect(sound, seen) {
        while (sound && seen) {
            tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 60)
            // Locked: quick steady beeps, a little quicker with a better SNR. No lock: slower
            // beeps that speed up with the strength.
            val wait = if (locked) (400 - snr * 3).coerceIn(100, 400) else (1500 - smooth * 10).toInt().coerceIn(250, 1500)
            delay(wait.toLong())
        }
    }

    // The phone stays awake while it is held up at the dish.
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    ModalBottomSheet(
        onDismissRequest = back,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("مؤشر القمر الصناعي", style = MaterialTheme.typography.headlineSmall)

            val scheme = MaterialTheme.colorScheme
            val ok = Color(0xFF2E7D32)
            val bad = scheme.error
            when {
                problem != null -> Text(problem!!, color = bad, style = MaterialTheme.typography.bodyLarge)
                !seen -> Text("جارٍ تشغيل القياس على المستقبل…", style = MaterialTheme.typography.bodyLarge)
                else -> Text(
                    if (locked) "✔ مقفول على القناة" else "✖ لا يوجد قفل",
                    color = if (locked) ok else bad,
                    style = MaterialTheme.typography.titleLarge,
                )
            }

            Text(
                if (seen) "${smooth.roundToInt()}" else "--",
                style = MaterialTheme.typography.displayLarge,
            )
            Meter("قوة الإشارة (مُنعَّمة)", smooth.roundToInt(), if (locked) ok else scheme.primary)
            Meter("الجودة SNR", snr, if (snr > 40) ok else scheme.tertiary)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("أعلى قراءة للقوة: $peak", style = MaterialTheme.typography.bodyLarge)
                TextButton(onClick = { peak = smooth.roundToInt() }) { Text("تصفير") }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("الصوت", style = MaterialTheme.typography.bodyLarge)
                Switch(checked = sound, onCheckedChange = { sound = it })
            }

            Text(
                "حرّك الدش ببطء: كلما أسرع الصوت زادت الإشارة. عند القفل تصبح الجودة SNR هي المرجع.",
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Meter(label: String, value: Int, color: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text("$value", style = MaterialTheme.typography.bodyMedium)
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(14.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(value.coerceIn(0, 100) / 100f)
                    .fillMaxHeight()
                    .background(color),
            )
        }
        Spacer(Modifier.height(2.dp))
    }
}
