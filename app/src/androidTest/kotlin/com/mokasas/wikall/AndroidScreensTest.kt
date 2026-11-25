package com.mokasas.wikall

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.IntentFilter
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import java.io.File
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidScreensTest {
    @get:Rule
    val app = createAndroidComposeRule<MainActivity>()

    @Test fun displaysTheServerAndClientActivationFlows() {
        app.onNodeWithText("إدارة الخادم · 99000").performClick()
        app.onNodeWithText("تشغيل الخادم").assertIsDisplayed()
        app.onNodeWithText("العودة لاختيار الوضع").performClick()
        app.onNodeWithText("الانضمام إلى خادم").performClick()
        app.onNodeWithText("استيراد ملف التفعيل").assertExists()
        val exports = File(app.activity.cacheDir, "exports/requests")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        // Intercept the system activity so the test keeps control of its own window.
        val chooser = instrumentation.addMonitor(
            IntentFilter(Intent.ACTION_CHOOSER), Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null), true,
        )
        try {
            app.onNodeWithText("طلب التفعيل").performScrollTo().performClick()
            app.waitUntil(15_000) { chooser.hits == 1 }
            val archive = checkNotNull(exports.listFiles()).first { it.extension == "zip" }
            DeviceRequest.read(unpackTransfer(Transfer.REQUEST, archive.readBytes()))
        } finally {
            instrumentation.removeMonitor(chooser)
        }
    }
}
