package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ChildDao {
    @Query("SELECT * FROM children")
    fun getAllChildren(): Flow<List<Child>>

    @Query("SELECT * FROM children WHERE childId = :id LIMIT 1")
    fun getChildById(id: String): Flow<Child?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChild(child: Child)

    @Query("DELETE FROM children WHERE childId = :id")
    suspend fun deleteChildById(id: String)
}
