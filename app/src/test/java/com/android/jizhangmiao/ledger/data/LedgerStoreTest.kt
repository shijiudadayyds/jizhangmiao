package com.android.jizhangmiao.ledger.data

import android.content.Context
import android.content.SharedPreferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.android.jizhangmiao.ledger.AutoImportedEntry
import com.android.jizhangmiao.ledger.data.room.LedgerDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LedgerStoreTest {
    @Test
    fun importAutoEntry_addsPendingImport_and_blocksDuplicateSignature() = withStore { store ->
        val imported = AutoImportedEntry(
            signature = "sig-1",
            type = LedgerEntryType.EXPENSE,
            amountInCents = 1_880L,
            account = "wechat",
            category = "daily",
            note = "auto import: wechat notice",
            receiptText = "paid 18.80",
            happenedAt = 1_710_000_000_000L
        )

        val firstImported = store.importAutoEntry(imported)
        val secondImported = store.importAutoEntry(imported)

        assertTrue(firstImported)
        assertFalse(secondImported)
        assertEquals(1, store.pendingImports.value.size)
        assertEquals("sig-1", store.pendingImports.value.first().signature)
        assertEquals("auto import: wechat notice", store.pendingImports.value.first().sourceLabel)
    }

    @Test
    fun approvePendingImport_movesCandidateIntoEntries() = withStore { store ->
        val imported = AutoImportedEntry(
            signature = "sig-approve",
            type = LedgerEntryType.INCOME,
            amountInCents = 2_500L,
            account = "alipay",
            category = "refund",
            note = "auto import: alipay notice",
            receiptText = "refund 25.00",
            happenedAt = 1_710_000_100_000L
        )

        assertTrue(store.importAutoEntry(imported))
        val pending = store.pendingImports.value.first()

        store.approvePendingImport(pending)

        assertTrue(store.pendingImports.value.isEmpty())
        assertEquals(1, store.entries.value.size)
        assertEquals(LedgerEntryType.INCOME, store.entries.value.first().type)
        assertEquals(2_500L, store.entries.value.first().amountInCents)
        assertEquals("refund", store.entries.value.first().category)
    }

    @Test
    fun importBackupJson_merge_combinesEntriesTemplatesBudgetsAndProfile() = withStore { existingStore ->
        existingStore.addEntry(
            LedgerEntry(
                id = "existing-entry",
                type = LedgerEntryType.EXPENSE,
                amountInCents = 1_880L,
                account = "wechat",
                category = "daily",
                happenedAt = 1_710_000_000_000L,
                updatedAt = 1_710_000_000_000L
            )
        )
        existingStore.upsertTemplate(
            LedgerTemplate(
                id = "existing-template",
                title = "breakfast",
                type = LedgerEntryType.EXPENSE,
                amountInCents = 1_200L,
                account = "wechat",
                category = "food",
                note = "weekday breakfast"
            )
        )
        existingStore.updateMonthlyBudget(300_000L)
        existingStore.updateCategoryBudget("food", 80_000L)
        existingStore.addAccount("bank")
        existingStore.addCategory(LedgerEntryType.EXPENSE, "travel")

        withStore { importedStore ->
            importedStore.addEntry(
                LedgerEntry(
                    id = "import-duplicate",
                    type = LedgerEntryType.EXPENSE,
                    amountInCents = 1_880L,
                    account = "wechat",
                    category = "daily",
                    happenedAt = 1_710_000_000_000L,
                    updatedAt = 1_710_000_000_000L
                )
            )
            importedStore.addEntry(
                LedgerEntry(
                    id = "import-new",
                    type = LedgerEntryType.INCOME,
                    amountInCents = 8_888L,
                    account = "alipay",
                    category = "bonus",
                    happenedAt = 1_710_000_200_000L,
                    updatedAt = 1_710_000_200_000L
                )
            )
            importedStore.upsertTemplate(
                LedgerTemplate(
                    id = "import-template",
                    title = "rent",
                    type = LedgerEntryType.EXPENSE,
                    amountInCents = 350_000L,
                    account = "bank",
                    category = "housing",
                    recurrence = LedgerTemplateRecurrence.MONTHLY,
                    nextDueAt = 1_710_100_000_000L
                )
            )
            importedStore.updateMonthlyBudget(500_000L)
            importedStore.updateCategoryBudget("housing", 350_000L)
            importedStore.addAccount("cash")
            importedStore.addCategory(LedgerEntryType.INCOME, "sidejob")

            val merged = existingStore.importBackupJson(
                importedStore.exportBackupJson(),
                LedgerImportMode.MERGE
            )

            assertTrue(merged)
            assertEquals(2, existingStore.entries.value.size)
            assertTrue(existingStore.entries.value.any { entry -> entry.id == "existing-entry" })
            assertTrue(existingStore.entries.value.any { entry -> entry.id == "import-new" })
            assertEquals(2, existingStore.templates.value.size)
            assertEquals(500_000L, existingStore.budgetConfig.value.monthlyBudgetInCents)
            assertEquals(80_000L, existingStore.budgetConfig.value.categoryBudgets["food"])
            assertEquals(350_000L, existingStore.budgetConfig.value.categoryBudgets["housing"])
            assertTrue(existingStore.profileConfig.value.customAccounts.containsAll(listOf("bank", "cash")))
            assertTrue(existingStore.profileConfig.value.customExpenseCategories.contains("travel"))
            assertTrue(existingStore.profileConfig.value.customIncomeCategories.contains("sidejob"))
        }
    }

    @Test
    fun importBackupJson_returnsFalse_whenJsonIsInvalid() = withStore { store ->
        val imported = store.importBackupJson("{not-valid-json}", LedgerImportMode.MERGE)

        assertFalse(imported)
        assertTrue(store.entries.value.isEmpty())
        assertTrue(store.templates.value.isEmpty())
    }

    @Test
    fun init_migratesLegacyPreferencesIntoRoom() = withStore(
        legacyPreferences = FakeSharedPreferences().apply {
            edit()
                .putString(
                    "entries",
                    LedgerJsonCodec.encodeEntries(
                        listOf(
                            LedgerEntry(
                                id = "legacy-entry",
                                type = LedgerEntryType.EXPENSE,
                                amountInCents = 4_200L,
                                account = "wechat",
                                category = "groceries",
                                happenedAt = 1_710_100_000_000L,
                                updatedAt = 1_710_100_000_000L
                            )
                        )
                    ).toString()
                )
                .putString(
                    "budget_config",
                    LedgerJsonCodec.encodeBudgetConfig(
                        LedgerBudgetConfig(
                            monthlyBudgetInCents = 200_000L,
                            categoryBudgets = sortedMapOf("groceries" to 50_000L)
                        )
                    ).toString()
                )
                .putString(
                    "profile_config",
                    LedgerJsonCodec.encodeProfileConfig(
                        LedgerProfileConfig(customAccounts = listOf("bank"))
                    ).toString()
                )
                .putString(
                    "security_config",
                    LedgerJsonCodec.encodeSecurityConfig(
                        LedgerSecurityConfig(pinHash = "hash", pinSalt = "salt")
                    ).toString()
                )
                .apply()
        }
    ) { store ->
        assertEquals(1, store.entries.value.size)
        assertEquals("legacy-entry", store.entries.value.first().id)
        assertEquals(200_000L, store.budgetConfig.value.monthlyBudgetInCents)
        assertEquals(50_000L, store.budgetConfig.value.categoryBudgets["groceries"])
        assertTrue(store.profileConfig.value.customAccounts.contains("bank"))
        assertTrue(store.securityConfig.value.isPinEnabled)
    }

    private fun withStore(
        legacyPreferences: SharedPreferences? = null,
        block: suspend (LedgerStore) -> Unit
    ) = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(
            context,
            LedgerDatabase::class.java
        ).allowMainThreadQueries().build()

        try {
            val store = LedgerStore(database, legacyPreferences)
            try {
                block(store)
            } finally {
                store.close()
            }
        } finally {
            database.close()
        }
    }

    private class FakeSharedPreferences : SharedPreferences {
        private val values = linkedMapOf<String, Any?>()

        override fun getAll(): MutableMap<String, *> = values.toMutableMap()

        override fun getString(
            key: String?,
            defValue: String?
        ): String? = values[key] as? String ?: defValue

        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(
            key: String?,
            defValues: MutableSet<String>?
        ): MutableSet<String>? {
            return ((values[key] as? Set<String>)?.toMutableSet() ?: defValues)
        }

        override fun getInt(
            key: String?,
            defValue: Int
        ): Int = values[key] as? Int ?: defValue

        override fun getLong(
            key: String?,
            defValue: Long
        ): Long = values[key] as? Long ?: defValue

        override fun getFloat(
            key: String?,
            defValue: Float
        ): Float = values[key] as? Float ?: defValue

        override fun getBoolean(
            key: String?,
            defValue: Boolean
        ): Boolean = values[key] as? Boolean ?: defValue

        override fun contains(key: String?): Boolean = values.containsKey(key)

        override fun edit(): SharedPreferences.Editor = Editor(values)

        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?
        ) = Unit

        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?
        ) = Unit

        private class Editor(
            private val values: MutableMap<String, Any?>
        ) : SharedPreferences.Editor {
            private val updates = linkedMapOf<String, Any?>()
            private val removals = linkedSetOf<String>()
            private var shouldClear = false

            override fun putString(
                key: String?,
                value: String?
            ): SharedPreferences.Editor = apply {
                if (key != null) {
                    updates[key] = value
                    removals.remove(key)
                }
            }

            override fun putStringSet(
                key: String?,
                values: MutableSet<String>?
            ): SharedPreferences.Editor = apply {
                if (key != null) {
                    updates[key] = values?.toSet()
                    removals.remove(key)
                }
            }

            override fun putInt(
                key: String?,
                value: Int
            ): SharedPreferences.Editor = apply {
                if (key != null) {
                    updates[key] = value
                    removals.remove(key)
                }
            }

            override fun putLong(
                key: String?,
                value: Long
            ): SharedPreferences.Editor = apply {
                if (key != null) {
                    updates[key] = value
                    removals.remove(key)
                }
            }

            override fun putFloat(
                key: String?,
                value: Float
            ): SharedPreferences.Editor = apply {
                if (key != null) {
                    updates[key] = value
                    removals.remove(key)
                }
            }

            override fun putBoolean(
                key: String?,
                value: Boolean
            ): SharedPreferences.Editor = apply {
                if (key != null) {
                    updates[key] = value
                    removals.remove(key)
                }
            }

            override fun remove(key: String?): SharedPreferences.Editor = apply {
                if (key != null) {
                    removals += key
                    updates.remove(key)
                }
            }

            override fun clear(): SharedPreferences.Editor = apply {
                shouldClear = true
                updates.clear()
                removals.clear()
            }

            override fun commit(): Boolean {
                if (shouldClear) {
                    values.clear()
                }
                removals.forEach(values::remove)
                updates.forEach { (key, value) ->
                    if (value == null) {
                        values.remove(key)
                    } else {
                        values[key] = value
                    }
                }
                return true
            }

            override fun apply() {
                commit()
            }
        }
    }
}
