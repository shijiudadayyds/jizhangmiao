package com.android.jizhangmiao.ledger.data

import java.util.UUID

enum class LedgerEntryType {
    EXPENSE,
    INCOME
}

data class LedgerEntry(
    val id: String = UUID.randomUUID().toString(),
    val type: LedgerEntryType,
    val amountInCents: Long,
    val category: String,
    val note: String = "",
    val happenedAt: Long = System.currentTimeMillis()
)
