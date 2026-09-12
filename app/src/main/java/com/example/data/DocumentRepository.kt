package com.example.data

import kotlinx.coroutines.flow.Flow

class DocumentRepository(private val documentDao: DocumentDao) {
    val allDocuments: Flow<List<Document>> = documentDao.getAllDocuments()

    fun getDocumentById(id: Int): Flow<Document?> = documentDao.getDocumentById(id)

    suspend fun insertDocument(document: Document): Long = documentDao.insertDocument(document)

    suspend fun deleteDocument(document: Document) = documentDao.deleteDocument(document)

    suspend fun deleteDocumentById(id: Int) = documentDao.deleteDocumentById(id)
}
