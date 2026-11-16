package com.example

import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.example.data.local.model.BoneSummary
import com.example.ui.components.BoneRow
import com.example.ui.theme.OrthoTipsTheme
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [36])
class GreetingScreenshotTest {

    @get:Rule val composeTestRule = createComposeRule()

    @Test
    fun bone_row_screenshot() {
        val sampleBone = BoneSummary(
            id = "bone_distal_radius_orif",
            chapterId = "chap_upper_limb",
            name = "Distal Radius Fracture ORIF",
            subtitle = "Volar Locking Plate & Modified Henry Approach",
            region = "Wrist & Hand",
            traumaType = "AO/OTA 23-C2 Complete Articular",
            classificationSummary = "Frykman Type VII / Fernandez Type II",
            isHighYield = true,
            isBookmarked = true,
            noteCount = 2
        )

        composeTestRule.setContent {
            OrthoTipsTheme {
                BoneRow(
                    bone = sampleBone,
                    onClick = {},
                    onBookmarkToggle = {},
                    modifier = Modifier.padding(16.dp)
                )
            }
        }

        composeTestRule.onRoot().captureRoboImage(filePath = "src/test/screenshots/bone_row.png")
    }
}
