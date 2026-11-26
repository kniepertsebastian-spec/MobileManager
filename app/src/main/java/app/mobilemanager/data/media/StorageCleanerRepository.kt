package app.mobilemanager.data.media

import android.os.Environment
import android.os.StatFs
import app.mobilemanager.data.local.CleanupHistoryDao
import app.mobilemanager.data.local.CleanupHistoryEntity
import app.mobilemanager.data.local.ImageAnalysisDao
import app.mobilemanager.data.local.ImageAnalysisEntity
import app.mobilemanager.domain.model.DuplicateGroup
import app.mobilemanager.domain.model.MediaImage
import app.mobilemanager.domain.model.ScanProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton

data class StorageInfo(val freeBytes: Long, val totalBytes: Long)

@Singleton
class StorageCleanerRepository @Inject constructor(
    private val scanner: MediaScanner,
    private val hasher: ImageHasher,
    private val faceAnalyzer: FaceAnalyzer,
    private val analysisDao: ImageAnalysisDao,
    private val historyDao: CleanupHistoryDao,
) {
    /** Scans teilen sich den Room-Cache und laufen daher nacheinander. */
    private val scanMutex = Mutex()

    val lastStorageCleanup: Flow<CleanupHistoryEntity?> = historyDao.observeLastStorageCleanup()

    fun storageInfo(): StorageInfo {
        val stat = StatFs(Environment.getDataDirectory().path)
        return StorageInfo(freeBytes = stat.availableBytes, totalBytes = stat.totalBytes)
    }

    /**
     * Zweistufige Duplikatsuche: zuerst nach identischer Dateigröße gruppieren, nur für Gruppen
     * mit ≥ 2 Dateien SHA-256 berechnen. Hashes werden in Room gecacht.
     */
    suspend fun findDuplicates(onProgress: (ScanProgress) -> Unit): List<DuplicateGroup> =
        scanMutex.withLock { withContext(Dispatchers.IO) {
            onProgress(ScanProgress("Bilder werden gelesen …", 0, 0))
            val images = scanner.queryImages()
            val cache = loadValidCache(images)

            val sizeGroups = images.filter { it.sizeBytes > 0 }
                .groupBy { it.sizeBytes }
                .values
                .filter { it.size >= 2 }
            val toHash = sizeGroups.flatten()

            val updates = ArrayList<ImageAnalysisEntity>()
            val hashes = HashMap<Long, String>()
            toHash.forEachIndexed { index, image ->
                ensureActive()
                onProgress(ScanProgress("Duplikate werden geprüft", index, toHash.size))
                val cached = cache[image.id]
                val hash = cached?.sha256 ?: hasher.sha256(image.uri)?.also { newHash ->
                    updates += (cached ?: newEntity(image)).copy(sha256 = newHash, updatedAt = now())
                }
                if (hash != null) hashes[image.id] = hash
            }
            if (updates.isNotEmpty()) analysisDao.upsertAll(updates)
            onProgress(ScanProgress("Duplikate werden geprüft", toHash.size, toHash.size))

            toHash.filter { it.id in hashes }
                .groupBy { hashes.getValue(it.id) }
                .filterValues { it.size >= 2 }
                .map { (hash, group) ->
                    // Ältestes Bild (Original) bleibt standardmäßig geschützt.
                    val sorted = group.sortedWith(compareBy<MediaImage> { it.dateTaken }.thenBy { it.id })
                    DuplicateGroup(sha256 = hash, keep = sorted.first(), duplicates = sorted.drop(1))
                }
                .sortedByDescending { it.wastedBytes }
        } }

    /**
     * Prüft alle Bilder älter als [ageMonths] Monate per ML Kit auf Gesichter.
     * Bereits analysierte (und unveränderte) Bilder werden übersprungen.
     * Die eigentliche Analyse läuft auf [Dispatchers.Default].
     * @return Anzahl der Bilder ohne Personen, die noch nicht bewertet wurden.
     */
    suspend fun analyzeOldImages(ageMonths: Int, onProgress: (ScanProgress) -> Unit): Int =
        scanMutex.withLock { withContext(Dispatchers.Default) {
            onProgress(ScanProgress("Bilder werden gelesen …", 0, 0))
            val images = scanner.queryImages()
            val cache = loadValidCache(images)
            val old = images.filter { it.dateTaken < cutoff(ageMonths) }
            val pending = old.filter { cache[it.id]?.hasFaces == null }

            val batch = ArrayList<ImageAnalysisEntity>()
            pending.forEachIndexed { index, image ->
                ensureActive()
                onProgress(ScanProgress("Personen-Erkennung (ML Kit)", index, pending.size))
                val hasFaces = faceAnalyzer.hasFaces(image.uri)
                if (hasFaces != null) {
                    batch += (cache[image.id] ?: newEntity(image)).copy(hasFaces = hasFaces, updatedAt = now())
                }
                if (batch.size >= DB_BATCH_SIZE) {
                    analysisDao.upsertAll(batch.toList())
                    batch.clear()
                }
            }
            if (batch.isNotEmpty()) analysisDao.upsertAll(batch)
            onProgress(ScanProgress("Personen-Erkennung (ML Kit)", pending.size, pending.size))
            reviewCandidates(ageMonths).size
        } }

    /** Alte Bilder ohne Personen, die der Nutzer noch nicht behalten hat. */
    suspend fun reviewCandidates(ageMonths: Int): List<MediaImage> = withContext(Dispatchers.IO) {
        val images = scanner.queryImages()
        val cache = loadValidCache(images)
        val cutoff = cutoff(ageMonths)
        images.filter { image ->
            val entry = cache[image.id]
            image.dateTaken < cutoff &&
                entry?.hasFaces == false &&
                entry.decision == ImageAnalysisEntity.DECISION_NONE
        }.sortedBy { it.dateTaken }
    }

    suspend fun markKeep(ids: List<Long>) {
        if (ids.isNotEmpty()) analysisDao.setDecision(ids, ImageAnalysisEntity.DECISION_KEEP, now())
    }

    suspend fun undoKeep(ids: List<Long>) {
        if (ids.isNotEmpty()) analysisDao.setDecision(ids, ImageAnalysisEntity.DECISION_NONE, now())
    }

    /** Nach bestätigter Löschung über den Systemdialog aufrufen. */
    suspend fun onImagesDeleted(images: List<MediaImage>) {
        if (images.isEmpty()) return
        analysisDao.deleteByIds(images.map { it.id })
        historyDao.insert(
            CleanupHistoryEntity(
                timestamp = now(),
                freedBytes = images.sumOf { it.sizeBytes },
                stoppedAppsCount = 0,
            ),
        )
    }

    /** Cache-Einträge, deren Bild unverändert existiert. Verwaiste Einträge werden entfernt. */
    private suspend fun loadValidCache(images: List<MediaImage>): Map<Long, ImageAnalysisEntity> {
        val byId = images.associateBy { it.id }
        val all = analysisDao.getAll()
        val stale = all.filter { it.mediaId !in byId }.map { it.mediaId }
        stale.chunked(SQL_CHUNK).forEach { analysisDao.deleteByIds(it) }
        return all.mapNotNull { entry ->
            val image = byId[entry.mediaId] ?: return@mapNotNull null
            if (image.dateModified == entry.dateModified && image.sizeBytes == entry.sizeBytes) {
                entry.mediaId to entry
            } else {
                null
            }
        }.toMap()
    }

    private fun newEntity(image: MediaImage) = ImageAnalysisEntity(
        mediaId = image.id,
        dateModified = image.dateModified,
        sizeBytes = image.sizeBytes,
        updatedAt = now(),
    )

    private fun cutoff(ageMonths: Int): Long =
        Calendar.getInstance().apply { add(Calendar.MONTH, -ageMonths) }.timeInMillis

    private fun now() = System.currentTimeMillis()

    private companion object {
        const val DB_BATCH_SIZE = 25
        const val SQL_CHUNK = 500
    }
}
