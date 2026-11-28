package app.mobilemanager.data.extras

import android.content.ContentValues
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Environment
import android.provider.MediaStore
import app.mobilemanager.domain.extras.SineWave
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.text.Collator
import javax.inject.Inject
import javax.inject.Singleton

/** Meilenstein 3.4.3: Lautsprecher-Reiniger (tiefer Sinuston auf voller Lautstärke). */
@Singleton
class SpeakerCleaner @Inject constructor(@ApplicationContext private val context: Context) {

    /** Kopfhörer würden bei voller Lautstärke den Ton direkt ins Ohr spielen. */
    fun headphonesConnected(): Boolean {
        val am = context.getSystemService(AudioManager::class.java) ?: return false
        val headphoneTypes = setOf(
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER,
            AudioDeviceInfo.TYPE_HEARING_AID,
        )
        return am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in headphoneTypes }
    }

    /** Spielt [seconds] lang [frequency] Hz; stellt danach die vorherige Lautstärke wieder her. Abbrechbar. */
    suspend fun run(frequency: Double = 165.0, seconds: Int = 15, onProgress: (Float) -> Unit) {
        val am = context.getSystemService(AudioManager::class.java) ?: return
        val stream = AudioManager.STREAM_MUSIC
        val previous = am.getStreamVolume(stream)
        val samples = withContext(Dispatchers.Default) { SineWave.generate(frequency, seconds, SAMPLE_RATE) }
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(samples.size * 2)
            .build()
        try {
            track.write(samples, 0, samples.size)
            runCatching { am.setStreamVolume(stream, am.getStreamMaxVolume(stream), 0) }
            track.play()
            val totalMs = seconds * 1000L
            var elapsed = 0L
            while (elapsed < totalMs) {
                onProgress(elapsed / totalMs.toFloat())
                delay(100)
                elapsed += 100
            }
            onProgress(1f)
        } finally {
            runCatching { track.stop() }
            track.release()
            runCatching { am.setStreamVolume(stream, previous, 0) }
        }
    }

    private companion object {
        const val SAMPLE_RATE = 44_100
    }
}

data class BackupApp(
    val packageName: String,
    val label: String,
    val versionName: String,
    val sizeBytes: Long,
    val splitCount: Int,
)

/** Meilenstein 3.4.4: kopiert base.apk (und Split-APKs) nach Documents/MobileManagerBackups. */
@Singleton
class ApkBackupRepository @Inject constructor(@ApplicationContext private val context: Context) {

    private val pm: PackageManager get() = context.packageManager

    suspend fun installedApps(): List<BackupApp> = withContext(Dispatchers.IO) {
        val collator = Collator.getInstance()
        pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(0)).mapNotNull { info ->
            val app = info.applicationInfo ?: return@mapNotNull null
            val isUserApp = app.flags and ApplicationInfo.FLAG_SYSTEM == 0 || app.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0
            if (!isUserApp || info.packageName == context.packageName) return@mapNotNull null
            val files = apkFiles(app)
            BackupApp(
                packageName = info.packageName,
                label = app.loadLabel(pm).toString(),
                versionName = info.versionName ?: info.longVersionCode.toString(),
                sizeBytes = files.sumOf { it.length() },
                splitCount = files.size - 1,
            )
        }.sortedWith { a, b -> collator.compare(a.label, b.label) }
    }

    private fun apkFiles(app: ApplicationInfo): List<File> =
        (listOf(app.sourceDir) + app.splitSourceDirs.orEmpty()).filterNotNull().map(::File).filter { it.canRead() }

    /** @return Ordner relativ zum internen Speicher, z. B. „Documents/MobileManagerBackups/Spotify_9.0.12“. */
    suspend fun backup(packageName: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val info = pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
            val app = info.applicationInfo ?: error("App nicht gefunden")
            val files = apkFiles(app)
            require(files.isNotEmpty()) { "APK-Datei nicht lesbar" }
            val folderName = sanitize("${app.loadLabel(pm)}_${info.versionName ?: info.longVersionCode}")
            val relativePath = "${Environment.DIRECTORY_DOCUMENTS}/$BACKUP_DIR/$folderName/"
            val resolver = context.contentResolver
            val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            files.forEach { file ->
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
                    put(MediaStore.MediaColumns.MIME_TYPE, APK_MIME)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val uri = resolver.insert(collection, values) ?: error("Speichern in Documents nicht möglich")
                try {
                    resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } } ?: error("Datei nicht beschreibbar")
                    resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                } catch (e: Exception) {
                    resolver.delete(uri, null, null)
                    throw e
                }
            }
            relativePath.trimEnd('/')
        }
    }

    private fun sanitize(name: String) = name.replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_').take(80)

    companion object {
        const val BACKUP_DIR = "MobileManagerBackups"
        private const val APK_MIME = "application/vnd.android.package-archive"
    }
}
