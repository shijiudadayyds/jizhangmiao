package com.android.jizhangmiao.ledger.data

import android.content.SharedPreferences
import com.android.jizhangmiao.ledger.AutoImportedEntry
import java.lang.reflect.Constructor
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LedgerStoreTest {
    @Test
    fun importAutoEntry_addsPendingImport_and_blocksDuplicateSignature() = runBlocking {
        val store = createLedgerStore()
        val imported = AutoImportedEntry(
            signature = "sig-1",
            type = LedgerEntryType.EXPENSE,
            amountInCents = 1_880L,
            account = "微信",
            category = "日用",
            note = "自动记账：微信支付 / 通知",
            receiptText = "支付成功\n￥18.80",
            happenedAt = 1_710_000_000_000L
        )

        val firstImported = store.importAutoEntry(imported)
        val secondImported = store.importAutoEntry(imported)

        assertTrue(firstImported)
        assertFalse(secondImported)
        assertEquals(1, store.pendingImports.value.size)
        assertEquals("sig-1", store.pendingImports.value.first().signature)
        assertEquals("自动记账：微信支付 / 通知", store.pendingImports.value.first().sourceLabel)
    }

    @Test
    fun approvePendingImport_movesCandidateIntoEntries() = runBlocking {
        val store = createLedgerStore()
        val imported = AutoImportedEntry(
            signature = "sig-approve",
            type = LedgerEntryType.INCOME,
            amountInCents = 2_500L,
            account = "支付宝",
            category = "退款",
            note = "自动记账：支付宝 / 通知",
            receiptText = "退款到账\n￥25.00",
            happenedAt = 1_710_000_100_000L
        )

        assertTrue(store.importAutoEntry(imported))
        val pending = store.pendingImports.value.first()

        store.approvePendingImport(pending)

        assertTrue(store.pendingImports.value.isEmpty())
        assertEquals(1, store.entries.value.size)
        assertEquals(LedgerEntryType.INCOME, store.entries.value.first().type)
        assertEquals(2_500L, store.entries.value.first().amountInCents)
        assertEquals("退款", store.entries.value.first().category)
    }

    @Test
    fun importBackupJson_merge_combinesEntriesTemplatesBudgetsAndProfile() = runBlocking {
        val existingStore = createLedgerStore()
        existingStore.addEntry(
            LedgerEntry(
                id = "existing-entry",
                type = LedgerEntryType.EXPENSE,
                amountInCents = 1_880L,
                account = "微信",
                category = "日用",
                happenedAt = 1_710_000_000_000L,
                updatedAt = 1_710_000_000_000L
            )
        )
        existingStore.upsertTemplate(
            LedgerTemplate(
                id = "existing-template",
                title = "早餐",
                type = LedgerEntryType.EXPENSE,
                amountInCents = 1_200L,
                account = "微信",
                category = "餐饮",
                note = "工作日早餐"
            )
        )
        existingStore.updateMonthlyBudget(300_000L)
        existingStore.updateCategoryBudget("餐饮", 80_000L)
        existingStore.addAccount("银行卡")
        existingStore.addCategory(LedgerEntryType.EXPENSE, "出行")

        val importedStore = createLedgerStore()
        importedStore.addEntry(
            LedgerEntry(
                id = "import-duplicate",
                type = LedgerEntryType.EXPENSE,
                amountInCents = 1_880L,
                account = "微信",
                category = "日用",
                happenedAt = 1_710_000_000_000L,
                updatedAt = 1_710_000_000_000L
            )
        )
        importedStore.addEntry(
            LedgerEntry(
                id = "import-new",
                type = LedgerEntryType.INCOME,
                amountInCents = 8_888L,
                account = "支付宝",
                category = "红包",
                happenedAt = 1_710_000_200_000L,
                updatedAt = 1_710_000_200_000L
            )
        )
        importedStore.upsertTemplate(
            LedgerTemplate(
                id = "import-template",
                title = "房租",
                type = LedgerEntryType.EXPENSE,
                amountInCents = 3_500_00L,
                account = "银行卡",
                category = "住房",
                recurrence = LedgerTemplateRecurrence.MONTHLY,
                nextDueAt = 1_710_100_000_000L
            )
        )
        importedStore.updateMonthlyBudget(500_000L)
        importedStore.updateCategoryBudget("住房", 350_000L)
        importedStore.addAccount("现金")
        importedStore.addCategory(LedgerEntryType.INCOME, "副业")

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
        assertEquals(80_000L, existingStore.budgetConfig.value.categoryBudgets["餐饮"])
        assertEquals(350_000L, existingStore.budgetConfig.value.categoryBudgets["住房"])
        assertTrue(existingStore.profileConfig.value.customAccounts.containsAll(listOf("银行卡", "现金")))
        assertTrue(existingStore.profileConfig.value.customExpenseCategories.contains("出行"))
        assertTrue(existingStore.profileConfig.value.customIncomeCategories.contains("副业"))
    }

    @Test
    fun importBackupJson_returnsFalse_whenJsonIsInvalid() = runBlocking {
        val store = createLedgerStore()

        val imported = store.importBackupJson("{not-valid-json}", LedgerImportMode.MERGE)

        assertFalse(imported)
        assertTrue(store.entries.value.isEmpty())
        assertTrue(store.templates.value.isEmpty())
    }

    private fun createLedgerStore(
        preferences: SharedPreferences = FakeSharedPreferences()
    ): LedgerStore {
        return ledgerStoreConstructor.newInstance(preferences)
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

    companion object {
        private val ledgerStoreConstructor: Constructor<LedgerStore> =
            LedgerStore::class.java.getDeclaredConstructor(SharedPreferences::class.java).apply {
                isAccessible = true
            }
    }
}
