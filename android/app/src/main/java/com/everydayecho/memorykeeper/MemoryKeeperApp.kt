package com.everydayecho.memorykeeper

import android.app.Application
import com.everydayecho.memorykeeper.data.FileRepository
import com.everydayecho.memorykeeper.data.SecurePreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class MemoryKeeperApp : Application() {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    lateinit var repository: FileRepository
        private set

    lateinit var securePrefs: SecurePreferences
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        repository = FileRepository(this)
        securePrefs = SecurePreferences(this)
        com.everydayecho.memorykeeper.transcribe.TranscribeWorker.schedule(this)
        appScope.launch(Dispatchers.IO) {
            repository.ensureSoul()
            repository.readMemory()
            repository.pruneOldTranscripts()
            repository.pruneOldRecordings()
        }
    }

    companion object {
        private lateinit var instance: MemoryKeeperApp
        fun get(): MemoryKeeperApp = instance
    }
}
