package com.lumenarcanus.dialife.core.ui

import android.content.Context
import android.text.format.DateFormat
import java.util.Date

/** "14:35" or "2:35 PM", as the phone is set. */
fun formatClock(ctx: Context, ms: Long): String = DateFormat.getTimeFormat(ctx).format(Date(ms))
