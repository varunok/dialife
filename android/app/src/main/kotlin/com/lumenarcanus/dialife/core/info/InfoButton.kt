package com.lumenarcanus.dialife.core.info

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumenarcanus.dialife.R
import com.lumenarcanus.dialife.core.ui.theme.DiaTheme
import com.lumenarcanus.dialife.data.MethodText
import com.lumenarcanus.dialife.data.Methods
import com.lumenarcanus.dialife.methodsRepository

/** The ⓘ next to a feature: taps open what [id] (a docs/METHODS.md section) is based on and what is our own. */
@Composable
fun InfoButton(id: String, modifier: Modifier = Modifier) {
    val c = DiaTheme.colors
    var open by rememberSaveable { mutableStateOf(false) }
    val about = stringResource(R.string.info_about)
    Text(
        "ⓘ",
        color = c.textMuted,
        fontSize = 16.sp,
        modifier = modifier
            .clickable { open = true }
            .semantics {
                role = Role.Button
                contentDescription = about
            }
            .padding(horizontal = 6.dp, vertical = 2.dp)
    )
    if (open) InfoSheet(id) { open = false }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InfoSheet(id: String, onClose: () -> Unit) {
    val c = DiaTheme.colors
    val repo = LocalContext.current.methodsRepository
    val loaded by produceState<Result<Methods>?>(null, repo) { value = repo.methods() }
    val lang = LocalConfiguration.current.locales[0].language
    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.surface
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            val methods = loaded?.getOrNull()
            val section = methods?.section(id, lang)
            when {
                loaded == null -> Text(stringResource(R.string.info_loading), color = c.textMuted, fontSize = 14.sp)
                section == null -> Text(stringResource(R.string.info_unavailable), color = c.textMuted, fontSize = 14.sp)
                else -> InfoContent(section, methods.link(id))
            }
            TextButton(onClick = onClose, modifier = Modifier.wrapContentSize()) { Text(stringResource(R.string.info_close)) }
        }
    }
}

/** A section: in short, what it is based on, what is our own, and the link to the full text. */
@Composable
fun InfoContent(s: MethodText, link: String?) {
    val c = DiaTheme.colors
    val uri = LocalUriHandler.current
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(s.title, color = c.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Text(s.short, color = c.textMuted, fontSize = 14.sp, lineHeight = 20.sp)
        Heading(stringResource(R.string.info_based))
        Items(s.based)
        Heading(stringResource(R.string.info_ours))
        Items(s.ours)
        link?.let {
            Text(
                stringResource(R.string.info_more),
                color = c.inRange,
                fontSize = 14.sp,
                modifier = Modifier.clickable { uri.openUri(it) }.padding(vertical = 4.dp)
            )
        }
    }
}

@Composable
private fun Heading(text: String) {
    Text(
        text,
        color = DiaTheme.colors.textFaint,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 4.dp)
    )
}

@Composable
private fun Items(items: List<String>) {
    val c = DiaTheme.colors
    if (items.isEmpty()) {
        Text(stringResource(R.string.info_none), color = c.textFaint, fontSize = 14.sp)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items.forEach { item ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("•", color = c.textMuted, fontSize = 14.sp)
                Text(item, color = c.text, fontSize = 14.sp, lineHeight = 20.sp)
            }
        }
    }
}
