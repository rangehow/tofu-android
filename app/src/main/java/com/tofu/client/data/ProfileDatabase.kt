package com.tofu.client.data

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [Profile::class], version = 1, exportSchema = false)
abstract class ProfileDatabase : RoomDatabase() {
    abstract fun profileDao(): ProfileDao
}
