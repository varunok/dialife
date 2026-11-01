package com.lumenarcanus.dialife.core.platform

import android.content.BroadcastReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Work a receiver does after onReceive returns: the system keeps the process for it until it ends. */
fun BroadcastReceiver.runAsync(block: suspend () -> Unit) {
    val done = goAsync()
    CoroutineScope(Dispatchers.Main).launch {
        try {
            block()
        } finally {
            done.finish()
        }
    }
}
