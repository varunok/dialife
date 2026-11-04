package com.lumenarcanus.dialife.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumenarcanus.dialife.R
import com.lumenarcanus.dialife.core.ui.theme.DiaTheme
import com.lumenarcanus.dialife.data.Untrusted

/** The sensor is not to be believed right now (a shower): a bright card, never a quiet hint. */
@Composable
fun UntrustedCard(w: Untrusted, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    WarnCard(stringResource(R.string.untrusted_title, formatClock(ctx, w.until)), stringResource(R.string.untrusted_text), modifier)
}

/** Amber, bordered, bold title: what must be read before trusting the numbers. */
@Composable
fun WarnCard(title: String?, text: String, modifier: Modifier = Modifier) {
    val c = DiaTheme.colors
    Column(
        modifier
            .fillMaxWidth()
            .background(c.highContainer, RoundedCornerShape(14.dp))
            .border(1.5.dp, c.high, RoundedCornerShape(14.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        title?.let { Text(it, color = c.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
        Text(text, color = c.text, fontSize = 13.5.sp, lineHeight = 19.sp)
    }
}
