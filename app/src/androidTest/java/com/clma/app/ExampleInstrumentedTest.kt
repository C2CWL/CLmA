package com.clma.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented test, which will execute on an Android device.
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
@RunWith(AndroidJUnit4::class)
class ExampleInstrumentedTest {
    @Test
    fun useAppContext() {
        // Context of the app under test.
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        // Compare against the applicationId (com.clma.app, debug adds ".debug"), not the
        // Gradle namespace — the two only coincide by choice.
        val pkg = appContext.packageName
        assertTrue(pkg, pkg == "com.clma.app" || pkg == "com.clma.app.debug")
    }
}
