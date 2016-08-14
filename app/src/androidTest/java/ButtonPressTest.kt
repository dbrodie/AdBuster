import android.content.Intent
import android.net.LocalSocketAddress
import android.os.SystemClock
import android.support.test.InstrumentationRegistry
import android.support.test.rule.ActivityTestRule
import android.support.test.runner.AndroidJUnit4
import android.test.suitebuilder.annotation.SmallTest
import app.adbuster.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import android.support.test.espresso.Espresso.onView
import android.support.test.espresso.matcher.ViewMatchers.*
import android.support.test.espresso.action.ViewActions.*
import android.support.test.uiautomator.By
import android.support.test.uiautomator.UiDevice
import android.support.test.uiautomator.UiSelector
import android.support.test.uiautomator.Until
import app.adbuster.R
import org.junit.Assert

@RunWith(AndroidJUnit4::class)
@SmallTest
class ButtonPressTest {

    @Test
    @Throws(Exception::class)
    fun ensureVpnStartsUp() {
        val dev = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        dev.pressHome()

        // Wait for launcher
        val launcherPackage = dev.launcherPackageName
        Assert.assertNotNull(launcherPackage)
        dev.wait(Until.hasObject(By.pkg(launcherPackage).depth(0)),
                1000)

        // Launch the app
        val context = InstrumentationRegistry.getContext()
        val intent = context.packageManager.getLaunchIntentForPackage("app.adbuster")
        // Clear out any previous instances
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
        context.startActivity(intent)

        // Wait for the app to appear
        dev.wait(Until.hasObject(By.pkg("app.adbuster").depth(0)),
                1000)

        val status = dev.findObject(UiSelector().descriptionContains("Vpn Status"))
        val btn = dev.findObject(UiSelector().descriptionContains("Toggle"))

        Assert.assertEquals(status.text, "Not running")

        btn.click()

        Assert.assertEquals(status.text, "Starting Ad Buster")

        val res = dev.wait(Until.hasObject(By.pkg("com.android.vpndialogs").clickable(true).text("OK")), 1000)
        if (res) {
            val ok_vpn = dev.findObject(UiSelector().packageName("com.android.vpndialogs").clickable(true).text("OK"))
            ok_vpn.click()
            SystemClock.sleep(500)
        }

        Assert.assertEquals(status.text, "I ain't 'fraid of no ads!")
    }
}