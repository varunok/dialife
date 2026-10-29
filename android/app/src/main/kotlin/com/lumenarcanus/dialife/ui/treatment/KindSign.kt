package com.lumenarcanus.dialife.ui.treatment

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumenarcanus.dialife.R
import com.lumenarcanus.dialife.data.InsulinType
import com.lumenarcanus.dialife.ui.theme.DiaTheme

/** How a kind of entry looks everywhere: its name, its chart letter and its colours. */
data class KindLook(val name: Int, val glyph: String, val ink: Color, val fill: Color)

@Composable
fun kindLook(kind: TreatmentKind, insulin: InsulinType = InsulinType.Rapid): KindLook {
    val c = DiaTheme.colors
    return when (kind) {
        TreatmentKind.Insulin -> if (insulin == InsulinType.Basal) {
            KindLook(R.string.tr_insulin, stringResource(R.string.glyph_basal), basalColor(c.isDark), basalContainer(c.isDark))
        } else KindLook(R.string.tr_insulin, stringResource(R.string.glyph_insulin), c.insulin, c.insulinContainer)
        TreatmentKind.Food -> KindLook(R.string.tr_food, stringResource(R.string.glyph_carbs), c.carbs, c.carbsContainer)
        TreatmentKind.Meter -> KindLook(R.string.tr_meter, stringResource(R.string.glyph_meter), c.text, c.surfaceRaised)
        TreatmentKind.Activity -> KindLook(R.string.tr_activity, stringResource(R.string.glyph_activity), c.inRange, c.inRangeContainer)
        TreatmentKind.Note -> KindLook(R.string.tr_note, stringResource(R.string.glyph_note), c.textMuted, c.surfaceRaised)
    }
}

/** The chart's letter in a small circle; on a coloured button it sits on the surface colour. */
@Composable
fun KindSign(glyph: String, ink: Color, size: Dp = 22.dp, background: Color = DiaTheme.colors.surface, ring: Color? = null) {
    Box(
        // the letter repeats the button's name: not read out
        Modifier.size(size).clearAndSetSemantics {}.background(background, CircleShape).then(if (ring != null) Modifier.border(2.dp, ring, CircleShape) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, color = ink, fontSize = (size.value * 0.48f).sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

/** Insulin's button: rapid and long-acting both, the form chooses. */
@Composable
fun InsulinSigns(size: Dp = 22.dp, behind: Color = DiaTheme.colors.insulinContainer) {
    val c = DiaTheme.colors
    Row(horizontalArrangement = Arrangement.spacedBy((-5).dp)) {
        KindSign(stringResource(R.string.glyph_insulin), c.insulin, size)
        KindSign(stringResource(R.string.glyph_basal), basalColor(c.isDark), size, ring = behind)
    }
}
