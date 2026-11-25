package com.mokasas.wikall

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
        app.onNodeWithText("إنشاء طلب جهاز").assertExists()
        app.onNodeWithText("استيراد ملف التفعيل").assertExists()
    }
}
