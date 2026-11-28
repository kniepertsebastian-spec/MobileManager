package app.mobilemanager.ui.external

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import app.mobilemanager.R
import app.mobilemanager.data.extras.ExifCleaner
import app.mobilemanager.domain.extras.ShareInputPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Phase 4.8: EXIF-Metadaten-Entferner im Teilen-Menü. Nimmt nur fremde `content://`-Bilder an (siehe
 * [ShareInputPolicy]), bereinigt sie mit [ExifCleaner] im privaten Cache und gibt sie über das normale
 * Teilen-Menü mit befristeter Leseberechtigung weiter.
 */
class ExifShareActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val incoming = runCatching {
            when (intent?.action) {
                Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
                Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
                else -> emptyList()
            }
        }.getOrDefault(emptyList())
        if (incoming.isEmpty() || savedInstanceState != null) {
            finish()
            return
        }
        val cleaner = ExifCleaner(applicationContext)
        lifecycleScope.launch {
            val (cleaned, rejected) = withContext(Dispatchers.IO) {
                val uris = incoming.take(ShareInputPolicy.MAX_ITEMS)
                val accepted = uris.filter(cleaner::accepts)
                val dir = cleaner.freshDirectory()
                val files = accepted.mapIndexedNotNull { index, uri -> runCatching { cleaner.clean(uri, dir, index) }.getOrNull() }
                files to (incoming.size - files.size)
            }
            if (cleaned.isEmpty()) {
                toast(getString(R.string.exif_share_failed))
                finish()
                return@launch
            }
            val contentUris = cleaned.map { FileProvider.getUriForFile(this@ExifShareActivity, "$packageName.files", it) }
            val send = if (contentUris.size == 1) {
                Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, contentUris.first())
            } else {
                Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(contentUris))
            }
            send.type = "image/*"
            send.clipData = ClipData.newRawUri(null, contentUris.first()).apply { contentUris.drop(1).forEach { addItem(ClipData.Item(it)) } }
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val done = resources.getQuantityString(R.plurals.exif_share_done, cleaned.size, cleaned.size)
            val skipped = if (rejected > 0) " " + resources.getQuantityString(R.plurals.exif_share_skipped, rejected, rejected) else ""
            toast(done + skipped)
            startActivity(Intent.createChooser(send, getString(R.string.exif_share_chooser)))
            finish()
        }
    }

    private fun toast(text: String) = Toast.makeText(applicationContext, text, Toast.LENGTH_SHORT).show()
}
