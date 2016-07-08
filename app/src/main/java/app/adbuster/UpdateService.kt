package app.adbuster

import android.app.DownloadManager
import android.app.IntentService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Parcel
import android.os.Parcelable
import android.support.v4.content.LocalBroadcastManager
import android.util.Log
import org.json.JSONArray
import java.net.URL

const val UPDATE_AVAILABLE_INTENT = "app.adbuster.UPDATE_AVAILABLE_INTENT"

class UpdateService : IntentService("UpdateService") {

    var mCurrentInstalledVersion : String? = null
        get() {
            val version = BuildConfig.VERSION_NAME.split("-")[0]
            return version
        }

    override fun onHandleIntent(intent: Intent?) {
        if (intent != null) {
            when (intent.action) {
                ACTION_FETCH_SERVER_VERSION -> {
                    handleActionFetchServerVersion()
                }
                ACTION_DOWNLOAD_UPDATE -> {
                    val url = intent.getStringExtra(EXTRA_NEW_VERSION_URL)
                    handleActionDownloadUpdate(url)
                }
                ACTION_INSTALL_UPDATE -> {
                    val uri = Uri.parse(intent.getStringExtra(EXTRA_NEW_VERSION_LOCAL_URI))
                    handleActionInstallUpdate(uri)
                }
            }
        }
    }

    private fun handleActionFetchServerVersion() {
        val request = URL("$BASE_API_URL/$API_URI").readText()
        val json = JSONArray(request).getJSONObject(0)
        val serverVersionInfo = VersionInfo(
                json.getString("tag_name").removePrefix("v"),
                json.getString("body"),
                json.getJSONArray("assets").getJSONObject(0).getString("browser_download_url")
        )

        Log.d(TAG, "Local version: $mCurrentInstalledVersion; Server Version: ${serverVersionInfo.version}")

        if (mCurrentInstalledVersion != serverVersionInfo.version) {
            Log.i(TAG, "An update is available (${serverVersionInfo.version}), sending broadcast")
            val intent = Intent(UPDATE_AVAILABLE_INTENT)
            intent.putExtra(EXTRA_NEW_VERSION_INFO, serverVersionInfo)
            LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
        }
    }

    private fun handleActionDownloadUpdate(url: String) {
        Log.d(TAG, "handleActionDownloadUpdate() received url: $url")
        val downloadManager = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
        val request = DownloadManager.Request(Uri.parse(url)).
                setVisibleInDownloadsUi(false)
        val downloadReference = downloadManager.enqueue(request)

        packageManager.setComponentEnabledSetting(
                ComponentName(this, DownloadCompleteReceiver::class.java),
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP)

        getSharedPreferences("myPref", MODE_PRIVATE)
                .edit()
                .putLong("downloadReference", downloadReference)
                .commit()
    }

    private fun handleActionInstallUpdate(uri: Uri) {
        disableDownloadCompleteReceiver(this)

        val installIntent = Intent(Intent.ACTION_VIEW)
        installIntent.setDataAndType(uri, "application/vnd.android.package-archive")
        installIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        this.startActivity(installIntent)
    }

    private fun disableDownloadCompleteReceiver(context: Context) {
        // Disable broadcast receiver, we don't need it anymore
        context.packageManager.setComponentEnabledSetting(
                ComponentName(context, DownloadCompleteReceiver::class.java),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                0)

    }

    companion object {
        private val TAG = "UpdateService"
        private val BASE_API_URL = "https://api.github.com"
        private val API_URI = "repos/dbrodie/adbuster/releases"

        private val ACTION_FETCH_SERVER_VERSION = "app.adbuster.action.FETCH_SERVER_VERSION"
        private val ACTION_DOWNLOAD_UPDATE = "app.adbuster.action.DOWNLOAD_UPDATE"
        private val ACTION_INSTALL_UPDATE = "app.adbuster.action.INSTALL_UPDATE"

        val EXTRA_NEW_VERSION_INFO = "app.adbuster.extra.NEW_VERSION_INFO"
        private val EXTRA_NEW_VERSION_URL = "app.adbuster.extra.NEW_VERSION_URL"
        private val EXTRA_NEW_VERSION_LOCAL_URI = "app.adbuster.extra.NEW_VERSION_LOCAL_URI"

        fun startActionFetchServerVersion(context: Context) {
            val intent = Intent(context, UpdateService::class.java)
            intent.action = ACTION_FETCH_SERVER_VERSION
            context.startService(intent)
        }

        fun startActionDownloadUpdate(context: Context, url: String) {
            val intent = Intent(context, UpdateService::class.java)
            intent.action = ACTION_DOWNLOAD_UPDATE
            intent.putExtra(EXTRA_NEW_VERSION_URL, url)
            context.startService(intent)
        }

        fun startActionInstallUpdate(context: Context, uri: Uri) {
            val intent = Intent(context, UpdateService::class.java)
            intent.action = ACTION_INSTALL_UPDATE
            intent.putExtra(EXTRA_NEW_VERSION_LOCAL_URI, uri.toString())
            context.startService(intent)
        }
    }
}

data class VersionInfo(val version: String, val releaseData: String, val downloadUrl: String) : Parcelable {
    constructor(source: Parcel): this(source.readString(), source.readString(), source.readString())

    override fun describeContents(): Int {
        return 0
    }

    override fun writeToParcel(dest: Parcel?, flags: Int) {
        dest?.writeString(version)
        dest?.writeString(releaseData)
        dest?.writeString(downloadUrl)
    }

    companion object {
        @JvmField final val CREATOR: Parcelable.Creator<VersionInfo> = object : Parcelable.Creator<VersionInfo> {
            override fun createFromParcel(source: Parcel): VersionInfo {
                return VersionInfo(source)
            }

            override fun newArray(size: Int): Array<VersionInfo?> {
                return arrayOfNulls(size)
            }
        }
    }
}
