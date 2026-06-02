package com.example

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.example.data.Recording
import com.example.data.RecordingDao
import com.example.data.RecordingRepository
import com.example.ui.ScreenRecorderMainScreen
import com.example.ui.theme.MyApplicationTheme
import com.example.viewmodel.ScreenRecorderViewModel
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.flow.flowOf
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
  fun greeting_screenshot() {
    val mockDao = object : RecordingDao {
        override fun getAllRecordings() = flowOf(emptyList<Recording>())
        override suspend fun getRecordingById(id: Long) = null
        override suspend fun insertRecording(recording: Recording) = 0L
        override suspend fun updateRecording(recording: Recording) {}
        override suspend fun deleteRecording(recording: Recording) {}
        override suspend fun deleteRecordingById(id: Long) {}
    }
    val mockRepo = RecordingRepository(mockDao)
    val mockViewModel = ScreenRecorderViewModel(mockRepo)

    composeTestRule.setContent { 
      MyApplicationTheme { 
        ScreenRecorderMainScreen(
            viewModel = mockViewModel,
            modifier = Modifier.fillMaxSize()
        ) 
      } 
    }

    composeTestRule.onRoot().captureRoboImage(filePath = "src/test/screenshots/greeting.png")
  }
}
