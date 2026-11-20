package io.github.iampopovich.eculense.core.screenshots

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.iampopovich.eculense.core.ui.theme.ECULenseTheme
import org.robolectric.RuntimeEnvironment

/** C13 screenshot matrix: two screen sizes × three font scales, Russian UI (the longest labels, Alex's language). */
object ScreenshotMatrix {
    data class Device(val name: String, val qualifiers: String)

    val PHONE = Device("phone", "w411dp-h891dp-xhdpi")
    val SMALL = Device("small", "w360dp-h640dp-xhdpi")
    val DEVICES = listOf(PHONE, SMALL)
    val FONT_SCALES = listOf(1.0f, 1.3f, 2.0f)

    /** Half resolution keeps the committed PNGs small; a small tolerance absorbs font antialiasing differences. */
    val OPTIONS = RoborazziOptions(
        recordOptions = RoborazziOptions.RecordOptions(resizeScale = 0.5),
        compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.01f),
    )

    fun fileName(name: String, device: Device, fontScale: Float): String =
        "src/test/snapshots/${name}_${device.name}_font${(fontScale * 10).toInt()}.png"
}

/**
 * Captures [content] for every device and font scale. Must run inside a Robolectric test with
 * `@GraphicsMode(GraphicsMode.Mode.NATIVE)`.
 */
fun snapshotMatrix(
    name: String,
    dark: Boolean = false,
    locale: String = "ru",
    devices: List<ScreenshotMatrix.Device> = ScreenshotMatrix.DEVICES,
    fontScales: List<Float> = ScreenshotMatrix.FONT_SCALES,
    content: @Composable () -> Unit,
) {
    for (device in devices) {
        for (scale in fontScales) {
            // Dates on screen must not depend on the machine: CI runs in UTC, the laptop does not.
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"))
            RuntimeEnvironment.setQualifiers("$locale-${device.qualifiers}")
            RuntimeEnvironment.setFontScale(scale)
            captureRoboImage(ScreenshotMatrix.fileName(name, device, scale), ScreenshotMatrix.OPTIONS) {
                ECULenseTheme(darkTheme = dark, dynamicColor = false) {
                    Surface(Modifier.fillMaxSize()) { content() }
                }
            }
        }
    }
}
