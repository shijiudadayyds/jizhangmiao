package com.android.jizhangmiao.ledger

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.android.jizhangmiao.ledger.data.LedgerStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class PaymentNotificationListenerService : NotificationListenerService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val candidate = parseAutoImportedEntry(sbn) ?: return
        serviceScope.launch {
            LedgerStore.getInstance(applicationContext).importAutoEntry(candidate)
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }
}
