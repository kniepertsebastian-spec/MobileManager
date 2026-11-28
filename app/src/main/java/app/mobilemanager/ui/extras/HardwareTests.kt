package app.mobilemanager.ui.extras

import androidx.compose.runtime.saveable.rememberSaveable
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.VibrationEffect
import android.os.VibratorManager
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import app.mobilemanager.domain.extras.SineWave
import app.mobilemanager.ui.components.SectionCard
import app.mobilemanager.ui.guard.GuardSubScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import kotlin.random.Random

/** Phase 4.8: interaktive Hardware-Tests – Touchscreen, Stereo-Lautsprecher, Vibration, Speichergeschwindigkeit. */
@Composable
fun HardwareTestsScreen(onBack: () -> Unit, onOpenTouchTest: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var speaker by rememberSaveable { mutableStateOf<String?>(null) }
    var storage by rememberSaveable { mutableStateOf<String?>(null) }

    GuardSubScreen(title = "Hardware-Tests", onBack = onBack) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionCard {
                Text("Touchscreen-Raster", style = MaterialTheme.typography.titleMedium)
                Text("Mit dem Finger über alle Felder wischen – Felder ohne Reaktion bleiben grau.", style = MaterialTheme.typography.bodySmall)
                Button(onClick = onOpenTouchTest, modifier = Modifier.padding(top = 8.dp)) { Text("Test starten") }
            }
            SectionCard {
                Text("Lautsprecher links/rechts", style = MaterialTheme.typography.titleMedium)
                Text("Spielt je 2 Sekunden einen Ton nur links, dann nur rechts (Stereo-Lautsprecher oder Kopfhörer).", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    OutlinedButton(onClick = {
                        scope.launch {
                            speaker = "Links …"
                            withContext(Dispatchers.Default) { playTone(left = true) }
                            speaker = "Rechts …"
                            withContext(Dispatchers.Default) { playTone(left = false) }
                            speaker = "Fertig – kamen beide Seiten getrennt an?"
                        }
                    }) { Text("Abspielen") }
                }
                speaker?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
            SectionCard {
                Text("Vibration", style = MaterialTheme.typography.titleMedium)
                Text("Kurz, lang und ein ansteigendes Muster.", style = MaterialTheme.typography.bodySmall)
                Button(onClick = {
                    val vibrator = context.getSystemService(VibratorManager::class.java)?.defaultVibrator
                    vibrator?.vibrate(
                        VibrationEffect.createWaveform(
                            longArrayOf(0, 80, 300, 600, 300, 150, 100, 150, 100, 150),
                            intArrayOf(0, 255, 0, 255, 0, 60, 0, 140, 0, 255),
                            -1,
                        ),
                    )
                }, modifier = Modifier.padding(top = 8.dp)) { Text("Vibrieren") }
            }
            SectionCard {
                Text("Speicher-Geschwindigkeit", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Schreibt 256 MB in den App-Cache und liest sie wieder (Lesen teils aus dem Arbeitsspeicher-Cache). Danach wird die Datei gelöscht.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(onClick = {
                    scope.launch {
                        storage = "Messe …"
                        storage = withContext(Dispatchers.IO) { measureStorage(File(context.cacheDir, "speedtest.bin")) }
                    }
                }, enabled = storage != "Messe …", modifier = Modifier.padding(top = 8.dp)) { Text("Messen") }
                storage?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            }
            Text("Mikrofon-Pegel ist bewusst nicht dabei – dafür bräuchte MobileManager die Mikrofon-Berechtigung.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun playTone(left: Boolean) {
    val rate = 44_100
    val mono = SineWave.generate(660.0, 2, rate, amplitude = 0.6)
    val stereo = ShortArray(mono.size * 2) { i -> if ((i % 2 == 0) == left) mono[i / 2] else 0 }
    val track = AudioTrack.Builder()
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
        .setAudioFormat(
            AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(rate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                .build(),
        )
        .setTransferMode(AudioTrack.MODE_STATIC)
        .setBufferSizeInBytes(stereo.size * 2)
        .build()
    try {
        track.write(stereo, 0, stereo.size)
        track.play()
        Thread.sleep(2_100)
        track.stop()
    } finally {
        track.release()
    }
}

private fun measureStorage(file: File): String {
    val size = 256L * 1024 * 1024
    val buffer = ByteArray(4 * 1024 * 1024).also { Random.nextBytes(it) }
    return try {
        val writeStart = System.nanoTime()
        FileOutputStream(file).use { out ->
            var written = 0L
            while (written < size) {
                out.write(buffer)
                written += buffer.size
            }
            out.fd.sync()
        }
        val writeSeconds = (System.nanoTime() - writeStart) / 1e9
        val readStart = System.nanoTime()
        RandomAccessFile(file, "r").use { raf ->
            while (raf.read(buffer) > 0) Unit
        }
        val readSeconds = (System.nanoTime() - readStart) / 1e9
        val mb = size / (1024.0 * 1024.0)
        "Schreiben: %.0f MB/s · Lesen: %.0f MB/s".format(mb / writeSeconds, mb / readSeconds)
    } catch (e: Exception) {
        "Fehler: ${e.message}"
    } finally {
        file.delete()
    }
}

/** Vollbild-Raster: jede berührte Zelle wird grün. */
@Composable
fun TouchTestScreen(onDone: () -> Unit) {
    val view = LocalView.current
    val activity = LocalActivity.current
    val columns = 8
    val rows = 14
    val touched = remember { mutableStateListOf<Int>() }
    DisposableEffect(Unit) {
        val controller = activity?.window?.let { WindowCompat.getInsetsController(it, view) }
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        var canvasSize by remember { mutableStateOf(Size.Zero) }
        fun mark(offset: Offset) {
            if (canvasSize.width <= 0f) return
            val col = (offset.x / (canvasSize.width / columns)).toInt().coerceIn(0, columns - 1)
            val row = (offset.y / (canvasSize.height / rows)).toInt().coerceIn(0, rows - 1)
            val index = row * columns + col
            if (index !in touched) touched += index
        }
        Canvas(
            Modifier.fillMaxSize()
                .pointerInput(Unit) { detectTapGestures { mark(it) } }
                .pointerInput(Unit) { detectDragGestures { change, _ -> mark(change.position) } },
        ) {
            canvasSize = size
            val w = size.width / columns
            val h = size.height / rows
            for (r in 0 until rows) for (c in 0 until columns) {
                drawRect(
                    color = if (r * columns + c in touched) Color(0xFF2E7D32) else Color(0xFF424242),
                    topLeft = Offset(c * w + 2, r * h + 2),
                    size = Size(w - 4, h - 4),
                )
            }
        }
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("${touched.size} / ${columns * rows}", color = Color.White, style = MaterialTheme.typography.headlineMedium)
            if (touched.size == columns * rows) Text("Alle Felder reagieren ✓", color = Color.White)
            OutlinedButton(onClick = onDone, modifier = Modifier.padding(top = 8.dp)) { Text("Beenden", color = Color.White) }
        }
    }
}
