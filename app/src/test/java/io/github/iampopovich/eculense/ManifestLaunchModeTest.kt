package io.github.iampopovich.eculense

import org.junit.Assert.assertEquals
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Регрессия: со standard launchMode тап по иконке (интент лаунчера не совпал с корневым интентом задачи — задача
 * открыта из уведомления, `am start` или MIUI добавил extras) клал НОВЫЙ MainActivity поверх старого с пустым
 * back stack → пользователь попадал на «Панель». singleTask: одна активность, повторный запуск идёт в onNewIntent.
 * Поведение задач проверяется только на устройстве (Robolectric в проекте нет), здесь — инвариант манифеста.
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
