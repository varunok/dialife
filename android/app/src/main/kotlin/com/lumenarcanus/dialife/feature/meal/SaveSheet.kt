package com.lumenarcanus.dialife.feature.meal

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumenarcanus.dialife.R
import com.lumenarcanus.dialife.core.ui.AlertCard
import com.lumenarcanus.dialife.core.ui.Units
import com.lumenarcanus.dialife.core.ui.formatAmount
import com.lumenarcanus.dialife.data.DoseCalc
import com.lumenarcanus.dialife.data.DoseSettings
import com.lumenarcanus.dialife.feature.meal.MealDraft
import com.lumenarcanus.dialife.feature.meal.MealDrafts
import com.lumenarcanus.dialife.ui.theme.DiaTheme

/** Before saving: what to know, an extra tap for a dose over the limit, and the choice to plan the dose or not. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SaveSheet(
    m: DoseCalc.Meal,
    dose: DoseSettings,
    units: Units,
    locale: java.util.Locale,
    planOn: Boolean,
    okBig: Boolean,
    onOkBig: (Boolean) -> Unit,
    onPlan: () -> Unit,
    onFoodOnly: () -> Unit,
    onBack: () -> Unit
) {
    val c = DiaTheme.colors
    fun f(x: Double) = formatAmount(x, locale)
    val needOk = m.level == DoseCalc.Level.High
    ModalBottomSheet(
        onDismissRequest = onBack,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.surface
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                stringResource(
                    if (planOn ||
                        !m.canPlan
                    ) {
                        R.string.meal_check_title
                    } else {
                        R.string.meal_plan_q
                    }
                ),
                color = c.text,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold
            )
            if (m.canPlan) Text(stringResource(R.string.meal_plan_q_text, f(m.toGive)), color = c.textMuted, fontSize = 13.5.sp)
            m.alerts.forEach { AlertCard(it, units, locale) }
            if (m.canPlan && needOk) {
                Row(
                    Modifier.fillMaxWidth().border(1.5.dp, c.low, RoundedCornerShape(14.dp)).clickable { onOkBig(!okBig) }.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    androidx.compose.material3.Checkbox(checked = okBig, onCheckedChange = onOkBig)
                    Text(
                        stringResource(R.string.meal_ok_big, f(m.toGive)),
                        color = c.text,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
            if (m.canPlan) {
                Button(
                    onClick = onPlan,
                    enabled = !needOk || okBig,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = c.insulin, contentColor = c.background)
                ) {
                    Text(
                        stringResource(if (m.asks) R.string.meal_plan_yes_seen else R.string.meal_plan_yes, f(m.toGive)),
                        fontWeight = FontWeight.SemiBold
                    )
                }
            } else if (m.level == DoseCalc.Level.Absurd) {
                Text(stringResource(R.string.meal_cannot_plan), color = c.low, fontSize = 13.5.sp)
            }
            OutlinedButton(onClick = onFoodOnly, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(16.dp)) {
                Text(stringResource(R.string.meal_food_only), color = c.carbs, fontWeight = FontWeight.SemiBold)
            }
            TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.tr_cancel)) }
        }
    }
}
