package app.adbuster

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkInfo
import org.junit.Assert
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricGradleTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkInfo

@RunWith(RobolectricGradleTestRunner::class)
@Config(constants = BuildConfig::class)
class VpnConfigurationTest {

    @Before
    fun before() {
        val cm = RuntimeEnvironment.application.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val shadowCM = Shadows.shadowOf(cm)
        val net_info = ShadowNetworkInfo.newInstance(
                NetworkInfo.DetailedState.CONNECTED,
                ConnectivityManager.TYPE_WIFI,
                0,
                true,
                true
        )

        shadowCM.addNetwork(
                ShadowNetwork.newInstance(0),
                net_info
        )

        shadowCM.setNetworkInfo(
                ConnectivityManager.TYPE_WIFI,
                net_info
        )
    }

    @Test
    fun dnsServerConfig_noServer() {
        try {
            getDnsServers(RuntimeEnvironment.application.applicationContext)
        } catch (e: VpnNetworkException) {
            return
        }
        Assert.assertTrue(false)
    }
}
