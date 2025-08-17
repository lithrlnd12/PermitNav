package com.permitnav.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.permitnav.data.models.Permit

@Database(
    entities = [Permit::class],
    version = 1,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class PermitNavDatabase : RoomDatabase() {
    
    abstract fun permitDao(): PermitDao
    
    companion object {
        @Volatile
        private var INSTANCE: PermitNavDatabase? = null
        
        fun getDatabase(context: Context): PermitNavDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    PermitNavDatabase::class.java,
                    "permitnav_database"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}