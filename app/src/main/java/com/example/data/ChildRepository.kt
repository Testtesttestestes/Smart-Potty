package com.example.data

import kotlinx.coroutines.flow.Flow

class ChildRepository(private val childDao: ChildDao) {
    val allChildren: Flow<List<Child>> = childDao.getAllChildren()

    suspend fun insert(child: Child) {
        childDao.insertChild(child)
    }

    suspend fun deleteById(id: String) {
        childDao.deleteChildById(id)
    }
}
