package app.adbuster

import android.app.DownloadManager
import android.app.IntentService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log

class DownloadCompleteReceiver() : BroadcastReceiver() {
    companion object {
        val TAG = "DownloadCompleteReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "onReceive()")
        if (intent.action == DownloadManager.ACTION_DOWNLOAD_COMPLETE) {
            val prefs = context.getSharedPreferences("myPref", Context.MODE_PRIVATE)
            val downloadReference = prefs.getLong("downloadReference", 0)

            if (downloadReference > 0) {
                val uri = getUriFromDownloadReference(context, intent, downloadReference)
                Log.d(TAG, "Calling startActionInstallUpdate() with uri: ${uri.toString()}")

                UpdateService.startActionInstallUpdate(context, uri)

                // Clear shared preferences, we don't need the download reference anymore
                prefs.edit()
                        .clear()
                        .apply()
            }
        }
    }

    private fun  getUriFromDownloadReference(context: Context, intent: Intent, downloadReference: Long): Uri {
        val downloadManager = context.getSystemService(IntentService.DOWNLOAD_SERVICE) as DownloadManager
        var uriString : String? = null
        if (intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) == downloadReference) {
            // Extract update file path and install it
            val query = DownloadManager.Query()
            query.setFilterById(downloadReference)
            val cursor = (downloadManager).query(query)
            if (cursor.moveToFirst()) {
                val columnIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                if (DownloadManager.STATUS_SUCCESSFUL == cursor.getInt(columnIndex)) {
                    uriString = cursor.getString(cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_FILENAME))
                }
            }
            cursor.close()
        }
        return (Uri.parse("file://" + uriString))
    }
}
