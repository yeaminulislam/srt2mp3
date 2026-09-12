package com.example.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface DocumentDao {
    @Query("SELECT * FROM documents ORDER BY lastModified DESC")
    fun getAllDocuments(): Flow<List<Document>>

    @Query("SELECT * FROM documents WHERE id = :id")
    fun getDocumentById(id: Int): Flow<Document?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDocument(document: Document): Long

    @Delete
    suspend fun deleteDocument(document: Document)

    @Query("DELETE FROM documents WHERE id = :id")
    suspend fun deleteDocumentById(id: Int)
}
