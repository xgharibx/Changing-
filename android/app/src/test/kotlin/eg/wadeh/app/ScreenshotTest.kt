package eg.wadeh.app

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import eg.wadeh.app.data.wireJson
import eg.wadeh.app.ui.*
import eg.wadeh.core.api.ListingCard
import eg.wadeh.core.api.ListingDetail
import eg.wadeh.core.api.Page
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Instant

/**
 * Pixel evidence rendered on the JVM (Robolectric native graphics) from JSON captured from the running dev API.
 * Complements, never replaces, the emulator run. Output: evidence/screens/<name>.png
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class ScreenshotTest {
    @get:Rule val rule = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private fun res(name: String) = javaClass.classLoader!!.getResource("api/$name")!!.readText()
    private fun save(name: String) {
        val dir = File(System.getProperty("screenshots.dir") ?: "build/screens").apply { mkdirs() }
        rule.waitForIdle()
        val v = rule.activity.window.decorView
        val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        v.draw(android.graphics.Canvas(bmp))
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun list(name: String) {
        val page = wireJson.decodeFromString(Page.serializer(ListingCard.serializer()), res("listings.json"))
        rule.setContent {
            WadehTheme {
                val ar = isArabic()
                LazyColumn(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(page.items) { ListingCardView(it, ar) {} }
                }
            }
        }
        save(name)
    }

    private fun detail(file: String, name: String) {
        val d = wireJson.decodeFromString(ListingDetail.serializer(), res(file))
        rule.setContent { WadehTheme { Box(Modifier.background(MaterialTheme.colorScheme.background)) { DetailBody(d, false, Instant.now(), isArabic()) {} } } }
        save(name)
    }

    @Test @Config(qualifiers = "en-w393dp-h1600dp-xhdpi") fun list_en() = list("list-en")
    @Test @Config(qualifiers = "ar-w393dp-h1600dp-xhdpi") fun list_ar() = list("list-ar")
    @Test @Config(qualifiers = "ar-w360dp-h740dp-night-xhdpi") fun list_ar_small_dark() = list("list-ar-small-dark")
    @Test @Config(qualifiers = "en-w393dp-h1900dp-xhdpi") fun detail_q1_en() = detail("detail-b12-0304-v1.json", "detail-b12-0304-v1-en")
    @Test @Config(qualifiers = "ar-w393dp-h1900dp-xhdpi") fun detail_q1_ar() = detail("detail-b12-0304-v1.json", "detail-b12-0304-v1-ar")
    @Test @Config(qualifiers = "ar-w360dp-h1900dp-xhdpi", fontScale = 1.5f) fun detail_unknown_parking_ar_large_font() = detail("detail-b12-0512.json", "detail-b12-0512-ar-font150")
}
