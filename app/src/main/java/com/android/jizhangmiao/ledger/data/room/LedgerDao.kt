package com.android.jizhangmiao.ledger.data.room

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
internal interface LedgerDao {
    @Query("SELECT * FROM ledger_entries ORDER BY happenedAt DESC, updatedAt DESC, id DESC")
    fun observeEntries(): Flow<List<LedgerEntryEntity>>

    @Query("SELECT * FROM ledger_entries ORDER BY happenedAt DESC, updatedAt DESC, id DESC")
    suspend fun getEntries(): List<LedgerEntryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertEntry(entry: LedgerEntryEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertEntries(entries: List<LedgerEntryEntity>)

    @Query("DELETE FROM ledger_entries WHERE id = :id")
    suspend fun deleteEntry(id: String)

    @Query("DELETE FROM ledger_entries")
    suspend fun deleteAllEntries()

    @Query("SELECT * FROM ledger_templates ORDER BY createdAt DESC, id DESC")
    fun observeTemplates(): Flow<List<LedgerTemplateEntity>>

    @Query("SELECT * FROM ledger_templates ORDER BY createdAt DESC, id DESC")
    suspend fun getTemplates(): List<LedgerTemplateEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTemplate(template: LedgerTemplateEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTemplates(templates: List<LedgerTemplateEntity>)

    @Query("DELETE FROM ledger_templates WHERE id = :id")
    suspend fun deleteTemplate(id: String)

    @Query("DELETE FROM ledger_templates")
    suspend fun deleteAllTemplates()

    @Query("SELECT * FROM ledger_pending_imports ORDER BY createdAt DESC, happenedAt DESC, id DESC")
    fun observePendingImports(): Flow<List<PendingLedgerImportEntity>>

    @Query("SELECT * FROM ledger_pending_imports ORDER BY createdAt DESC, happenedAt DESC, id DESC")
    suspend fun getPendingImports(): List<PendingLedgerImportEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPendingImports(pendingImports: List<PendingLedgerImportEntity>)

    @Query("DELETE FROM ledger_pending_imports WHERE id = :id")
    suspend fun deletePendingImport(id: String)

    @Query("DELETE FROM ledger_pending_imports")
    suspend fun deleteAllPendingImports()

    @Query("SELECT * FROM ledger_metadata WHERE singletonId = 1")
    fun observeMetadata(): Flow<LedgerMetadataEntity?>

    @Query("SELECT * FROM ledger_metadata WHERE singletonId = 1")
    suspend fun getMetadata(): LedgerMetadataEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMetadata(metadata: LedgerMetadataEntity)

    @Query("SELECT * FROM ledger_auto_import_history ORDER BY createdAt ASC, signature ASC")
    suspend fun getAutoImportHistory(): List<AutoImportHistoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAutoImportHistory(entity: AutoImportHistoryEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAutoImportHistory(entities: List<AutoImportHistoryEntity>)

    @Query("DELETE FROM ledger_auto_import_history WHERE signature = :signature")
    suspend fun deleteAutoImportSignature(signature: String)

    @Query("DELETE FROM ledger_auto_import_history")
    suspend fun deleteAllAutoImportHistory()
}
