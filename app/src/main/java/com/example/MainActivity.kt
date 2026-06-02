package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.data.RecordingDatabase
import com.example.data.RecordingRepository
import com.example.ui.ScreenRecorderMainScreen
import com.example.ui.theme.MyApplicationTheme
import com.example.viewmodel.ScreenRecorderViewModel

class ScreenRecorderViewModelFactory(private val repository: RecordingRepository) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ScreenRecorderViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return ScreenRecorderViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val database = RecordingDatabase.getDatabase(this)
        val repository = RecordingRepository(database.recordingDao())
        val factory = ScreenRecorderViewModelFactory(repository)
        val viewModel = ViewModelProvider(this, factory)[ScreenRecorderViewModel::class.java]

        setContent {
            MyApplicationTheme {
                ScreenRecorderMainScreen(
                    viewModel = viewModel,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}
