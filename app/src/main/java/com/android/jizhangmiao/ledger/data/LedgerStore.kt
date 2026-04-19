package com.android.jizhangmiao.ledger.data

import android.content.Context
import android.content.SharedPreferences
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class LedgerStore private constructor(
    private val preferences: SharedPreferences
) {
    private val writeLock = Any()
    private val _entries = MutableStateFlow(loadEntries())

    val entries: StateFlow<List<LedgerEntry>> = _entries.asStateFlow()

    suspend fun addEntry(entry: LedgerEntry) {
        updateEntries { current ->
            current + entry.copy(id = entry.id.ifBlank { UUID.randomUUID().toString() })
        }
    }

    suspend fun deleteEntry(entry: LedgerEntry) {
        updateEntries { current ->
            current.filterNot { savedEntry -> savedEntry.id == entry.id }
        }
    }

    private suspend fun updateEntries(
        transform: (List<LedgerEntry>) -> List<LedgerEntry>
    ) {
        withContext(Dispatchers.IO) {
            synchronized(writeLock) {
                val updatedEntries = transform(_entries.value)
                    .sortedWith(compareByDescending<LedgerEntry> { it.happenedAt }.thenByDescending { it.id })

                persistEntries(updatedEntries)
                _entries.value = updatedEntries
            }
        }
    }

    private fun loadEntries(): List<LedgerEntry> {
        val rawValue = preferences.getString(KEY_ENTRIES, null).orEmpty()
        if (rawValue.isBlank()) {
            return emptyList()
        }

        return runCatching {
            val jsonArray = JSONArray(rawValue)
            buildList {
                for (index in 0 until jsonArray.length()) {
                    val item = jsonArray.getJSONObject(index)
                    add(
                        LedgerEntry(
                            id = item.optString("id"),
                            type = LedgerEntryType.valueOf(item.getString("type")),
                            amountInCents = item.getLong("amountInCents"),
                            category = item.getString("category"),
                            note = item.optString("note"),
                            happenedAt = item.getLong("happenedAt")
                        )
                    )
                }
            }
        }.getOrElse {
            emptyList()
        }.sortedWith(compareByDescending<LedgerEntry> { it.happenedAt }.thenByDescending { it.id })
    }

    private fun persistEntries(entries: List<LedgerEntry>) {
        val jsonArray = JSONArray()
        entries.forEach { entry ->
            jsonArray.put(
                JSONObject().apply {
                    put("id", entry.id)
                    put("type", entry.type.name)
                    put("amountInCents", entry.amountInCents)
                    put("category", entry.category)
                    put("note", entry.note)
                    put("happenedAt", entry.happenedAt)
                }
            )
        }

        preferences.edit()
            .putString(KEY_ENTRIES, jsonArray.toString())
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "ledger_store"
        private const val KEY_ENTRIES = "entries"

        @Volatile
        private var instance: LedgerStore? = null

        fun getInstance(context: Context): LedgerStore {
            return instance ?: synchronized(this) {
                instance ?: LedgerStore(
                    preferences = context.applicationContext.getSharedPreferences(
                        PREFS_NAME,
                        Context.MODE_PRIVATE
                    )
                ).also { createdStore ->
                    instance = createdStore
                }
            }
        }
    }
}
