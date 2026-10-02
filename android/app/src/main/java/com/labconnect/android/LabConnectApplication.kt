package com.labconnect.android

import android.app.Application
import androidx.room.Room
import com.labconnect.android.data.LabConnectDatabase

class LabConnectApplication : Application() {
    
    companion object {
        @Volatile
        private var INSTANCE: LabConnectApplication? = null
        
        fun getInstance(): LabConnectApplication = INSTANCE!!
    }
    
    private var database: LabConnectDatabase? = null
    
    override fun onCreate() {
        super.onCreate()
        INSTANCE = this
        
        // Initialize database
        database = Room.databaseBuilder(
            this,
            LabConnectDatabase::class.java,
            "labconnect.db"
        ).build()
    }
    
    fun getDatabase(): LabConnectDatabase = database!!
}