package com.example.data

import kotlinx.coroutines.flow.Flow

class RecordingRepository(private val recordingDao: RecordingDao) {
    val allRecordings: Flow<List<Recording>> = recordingDao.getAllRecordings()

    suspend fun getRecordingById(id: Long): Recording? {
        return recordingDao.getRecordingById(id)
    }

    suspend fun insertRecording(recording: Recording): Long {
        return recordingDao.insertRecording(recording)
    }

    suspend fun updateRecording(recording: Recording) {
        recordingDao.updateRecording(recording)
    }

    suspend fun deleteRecording(recording: Recording) {
        recordingDao.deleteRecording(recording)
    }

    suspend fun deleteRecordingById(id: Long) {
        recordingDao.deleteRecordingById(id)
    }
}
