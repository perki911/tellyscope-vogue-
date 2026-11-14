// SPDX-License-Identifier: GPL-3.0-only
package app.tellyscope.ui

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Pointer travel for one radian of turn when the phone is the air mouse, before the speed slider. */
private const val AIR_PX_PER_RAD = 900f

/** Minutes the sleep timer offers. */
private val SLEEP_MINUTES = listOf(15, 30, 60, 90, 120)

/**
 * The TV's pointer from the phone, as a touch pad or, with the gyroscope, an air mouse held flat
 * like a remote; and the sleep timer. The pointer moves by writing relative motion into the
 * remote's input device on the TV, the same events its own mouse mode makes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MouseSheet(
    move: suspend (Int, Int) -> Boolean,
    click: suspend () -> Boolean,
    sleepIn: suspend (Int) -> Boolean,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val pending = remember { FloatArray(2) }
    var speed by remember { mutableFloatStateOf(1.5f) }
    var air by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf("") }
    val gyro = remember {
        (context.getSystemService(Context.SENSOR_SERVICE) as SensorManager).getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    }

    // Sends what the touch pad or the gyroscope gathered. Waiting for the TV's answer each time keeps it from queuing up.
    LaunchedEffect(Unit) {
        while (true) {
            delay(25)
            val dx = pending[0].roundToInt()
            val dy = pending[1].roundToInt()
            if (dx != 0 || dy != 0) {
                pending[0] -= dx
                pending[1] -= dy
                failed = try {
                    !move(dx, dy)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    true
                }
            }
        }
    }

    DisposableEffect(air, gyro) {
        if (!air || gyro == null) return@DisposableEffect onDispose { }
        val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        var last = 0L
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                if (last != 0L) {
                    val dt = (e.timestamp - last) / 1e9f
                    // Held flat, top toward the TV: turning left or right is the z axis, tilting up or down the x axis.
                    pending[0] += -e.values[2] * dt * AIR_PX_PER_RAD * speed
                    pending[1] += -e.values[0] * dt * AIR_PX_PER_RAD * speed
                }
                last = e.timestamp
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        manager.registerListener(listener, gyro, SensorManager.SENSOR_DELAY_GAME)
        onDispose { manager.unregisterListener(listener) }
    }

    fun clickNow() {
        scope.launch {
            failed = try {
                !click()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                true
            }
        }
    }

    fun arm(minutes: Int) {
        scope.launch {
            val ok = try {
                sleepIn(minutes)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }
            note = when {
                !ok -> "تعذّر ضبط المؤقت على التلفاز"
                minutes <= 0 -> "أُلغي المؤقت"
                else -> "سيُطفأ الجهاز بعد $minutes دقيقة إن كان يعمل"
            }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("الفأرة", style = MaterialTheme.typography.titleLarge)
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(240.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(MaterialTheme.colorScheme.secondaryContainer)
                    .pointerInput(Unit) { detectTapGestures(onTap = { clickNow() }) }
                    .pointerInput(Unit) {
                        detectDragGestures { change, drag ->
                            change.consume()
                            pending[0] += drag.x * speed
                            pending[1] += drag.y * speed
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (failed) "تعذّر الإرسال إلى التلفاز" else "حرّك إصبعك هنا، وانقر للنقر",
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            Button(onClick = { clickNow() }, modifier = Modifier.fillMaxWidth()) { Text("نقر") }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (gyro == null) "لا يوجد جيروسكوب في هذا الهاتف" else "فأرة هوائية (أمسك الهاتف مسطحاً كالريموت)",
                    Modifier.weight(1f),
                )
                Switch(checked = air, onCheckedChange = { air = it }, enabled = gyro != null)
            }
            Text("السرعة")
            Slider(value = speed, onValueChange = { speed = it }, valueRange = 0.5f..4f)

            Text("مؤقت النوم (دقيقة)", style = MaterialTheme.typography.titleMedium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SLEEP_MINUTES.forEach { m ->
                    OutlinedButton(onClick = { arm(m) }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(0.dp)) {
                        Text("$m")
                    }
                }
            }
            OutlinedButton(onClick = { arm(0) }, modifier = Modifier.fillMaxWidth()) { Text("إلغاء المؤقت") }
            if (note.isNotEmpty()) Text(note, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
