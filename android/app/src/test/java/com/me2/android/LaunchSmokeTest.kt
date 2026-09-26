package com.me2.android

import android.content.Intent
import android.os.Looper
import com.me2.android.data.SessionStorage
import com.me2.android.data.UserSession
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LaunchSmokeTest {
    @Test
    fun loginActivityInflates() {
        val activity = Robolectric.buildActivity(LoginActivity::class.java).setup().get()
        assertNotNull(activity)
        assertFalse(activity.isFinishing)
        assertNotNull(activity.findViewById(R.id.previewDemoButton))
        assertNotNull(activity.findViewById(R.id.logoImage))
    }

    @Test
    fun mainActivityOpensWithDemoSession() {
        val app = RuntimeEnvironment.getApplication()
        SessionStorage(app).clear()
        SessionStorage(app).saveUserCommit(UserSession.demoPreview())
        val intent = Intent(app, MainActivity::class.java).putExtra(LoginActivity.EXTRA_DEMO_PREVIEW, true)
        val activity = Robolectric.buildActivity(MainActivity::class.java, intent).setup().get()
        shadowOf(Looper.getMainLooper()).idle()
        assertNotNull(activity)
        assertFalse("MainActivity finished unexpectedly", activity.isFinishing)
        assertNotNull(activity.findViewById(R.id.playerView))
        assertNotNull(activity.findViewById(R.id.chatRecyclerView))
        assertNotNull(activity.findViewById(R.id.homeWidgetSwitch))
        assertNotNull(activity.findViewById(R.id.bitacoraPanel))
    }
}
