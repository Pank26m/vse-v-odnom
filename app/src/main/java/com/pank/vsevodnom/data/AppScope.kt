package com.pank.vsevodnom.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Область для записей в базу из диалогов. Область самого диалога отменяется,
 * как только он закрывается, — и запись могла не успеть сохраниться.
 */
object AppScope { val io = CoroutineScope(SupervisorJob() + Dispatchers.IO) }
