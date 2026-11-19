package io.github.iampopovich.eculense

import org.junit.Assert.assertEquals
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Regression: with standard launchMode, tapping the icon (when the launcher intent did not match the task's root
 * intent — the task was opened from a notification, `am start`, or MIUI added extras) put a NEW MainActivity on top
 * of the old one with an empty back stack → the user landed on «Панель». singleTask: one activity, relaunch goes to onNewIntent.
 * Task behavior can only be checked on a device (the project has no Robolectric); here we check the manifest invariant.
 */
class ManifestLaunchModeTest {
    private val android = "http://schemas.android.com/apk/res/android"

    private fun mainActivity(): Element {
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val doc = factory.newDocumentBuilder().parse(File("src/main/AndroidManifest.xml"))
        val activities = doc.getElementsByTagName("activity")
        return (0 until activities.length).map { activities.item(it) as Element }
            .single { it.getAttributeNS(android, "name") == ".MainActivity" }
    }

    @Test
    fun `main activity is single task so the launcher icon resumes the existing back stack`() {
        assertEquals("singleTask", mainActivity().getAttributeNS(android, "launchMode"))
    }
}
