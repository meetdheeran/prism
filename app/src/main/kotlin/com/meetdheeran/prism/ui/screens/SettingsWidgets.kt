package com.meetdheeran.prism.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBackIosNew
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.meetdheeran.prism.ui.glass.BackdropState
import com.meetdheeran.prism.ui.glass.GlassBackground
import com.meetdheeran.prism.ui.glass.GlassStyle
import com.meetdheeran.prism.ui.glass.LiquidGlass
import com.meetdheeran.prism.ui.glass.rememberBackdropState
import com.meetdheeran.prism.ui.motion.pressable
import com.meetdheeran.prism.ui.theme.LocalAccent
import com.meetdheeran.prism.ui.theme.PrismColors
import com.meetdheeran.prism.ui.theme.PrismTypography

/** iOS-Settings-style page: large title, glass groups, everything scrolls under the status bar. */
@Composable
fun ScreenScaffold(
    title: String,
    onBack: (() -> Unit)?,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.(BackdropState) -> Unit,
) {
    val backdrop = rememberBackdropState()
    val accent = LocalAccent.current
    Box(Modifier.fillMaxSize()) {
        GlassBackground(backdrop, accent = accent, animated = false, intensity = 0.9f)
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp),
        ) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (onBack != null) {
                    GlassIconButton(backdrop, Icons.Rounded.ArrowBackIosNew, "Back", onClick = onBack)
                    Spacer(Modifier.width(12.dp))
                }
                Text(title, style = PrismTypography.displayMedium, modifier = Modifier.weight(1f))
                actions()
            }
            Spacer(Modifier.height(18.dp))
            content(backdrop)
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
fun GlassIconButton(backdrop: BackdropState, icon: ImageVector, contentDescription: String, size: Int = 42, onClick: () -> Unit) {
    LiquidGlass(backdrop, Modifier.size(size.dp).pressable(onClick = onClick), CircleShape, GlassStyle.Tile) {
        Icon(icon, contentDescription, tint = PrismColors.TextPrimary, modifier = Modifier.align(Alignment.Center).size(18.dp))
    }
}

@Composable
fun GlassGroup(backdrop: BackdropState, title: String? = null, footer: String? = null, content: @Composable ColumnScope.() -> Unit) {
    if (title != null) {
        Text(title.uppercase(), style = PrismTypography.labelSmall, color = PrismColors.TextTertiary, modifier = Modifier.padding(start = 16.dp, bottom = 6.dp))
    }
    LiquidGlass(backdrop, Modifier.fillMaxWidth(), RoundedCornerShape(22.dp), GlassStyle.Regular.copy(blurRadius = 16.dp, refraction = 0.04f, elevation = 12.dp)) {
        Column(content = content)
    }
    if (footer != null) {
        Text(footer, style = PrismTypography.bodySmall, color = PrismColors.TextTertiary, modifier = Modifier.padding(start = 16.dp, top = 6.dp, end = 12.dp))
    }
    Spacer(Modifier.height(22.dp))
}

@Composable
fun GlassDivider() {
    Box(Modifier.fillMaxWidth().padding(start = 16.dp).height(0.5.dp).background(Color.White.copy(alpha = 0.14f)))
}

@Composable
fun SettingRow(
    title: String,
    subtitle: String? = null,
    icon: ImageVector? = null,
    value: String? = null,
    chevron: Boolean = false,
    danger: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val base = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 13.dp)
    Row(
        if (onClick != null) Modifier.pressable(scaleDown = 0.985f, onClick = onClick).then(base) else base,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = if (danger) PrismColors.Bad else LocalAccent.current, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = PrismTypography.bodyLarge, color = if (danger) PrismColors.Bad else PrismColors.TextPrimary)
            if (subtitle != null) Text(subtitle, style = PrismTypography.bodySmall, color = PrismColors.TextSecondary)
        }
        if (value != null) Text(value, style = PrismTypography.bodyMedium, color = PrismColors.TextSecondary, modifier = Modifier.padding(end = 6.dp))
        if (trailing != null) trailing()
        if (chevron) Icon(Icons.Rounded.ChevronRight, null, tint = PrismColors.TextTertiary)
    }
}

@Composable
fun SwitchRow(title: String, subtitle: String? = null, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    SettingRow(title, subtitle, onClick = { if (enabled) onChange(!checked) }, trailing = {
        Switch(
            checked = checked, onCheckedChange = onChange, enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedTrackColor = PrismColors.Good, checkedThumbColor = Color.White,
                uncheckedTrackColor = Color.White.copy(alpha = 0.18f), uncheckedThumbColor = Color.White.copy(alpha = 0.85f),
                uncheckedBorderColor = Color.Transparent,
            ),
        )
    })
}

/** iOS-style segmented control. */
@Composable
fun Segmented(options: List<String>, selected: Int, modifier: Modifier = Modifier, onSelect: (Int) -> Unit) {
    Row(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.10f))
            .padding(3.dp),
    ) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (on) Color.White.copy(alpha = 0.22f) else Color.Transparent)
                    .pressable(scaleDown = 0.97f) { onSelect(i) }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = PrismTypography.labelLarge, color = if (on) PrismColors.TextPrimary else PrismColors.TextSecondary)
            }
        }
    }
}

@Composable
fun GlassButton(text: String, modifier: Modifier = Modifier, filled: Boolean = true, danger: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val accent = LocalAccent.current
    val bg = when {
        !enabled -> Color.White.copy(alpha = 0.08f)
        danger -> PrismColors.Bad.copy(alpha = 0.85f)
        filled -> accent
        else -> Color.White.copy(alpha = 0.14f)
    }
    Box(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(bg)
            .pressable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = PrismTypography.labelLarge, color = if (enabled) Color.White else PrismColors.TextTertiary)
    }
}

/** A dark list dialog; label + optional note per option. */
@Composable
fun ChoiceDialog(
    title: String,
    options: List<Pair<String, String?>>,
    selected: Int,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PrismColors.Slate,
        titleContentColor = PrismColors.TextPrimary,
        textContentColor = PrismColors.TextSecondary,
        title = { Text(title, style = PrismTypography.titleLarge) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                options.forEachIndexed { i, (label, note) ->
                    Row(
                        Modifier.fillMaxWidth().pressable(scaleDown = 0.98f) { onPick(i) }.padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(label, style = PrismTypography.bodyLarge, color = PrismColors.TextPrimary)
                            if (!note.isNullOrBlank()) Text(note, style = PrismTypography.bodySmall, color = PrismColors.TextSecondary)
                        }
                        if (i == selected) Text("✓", color = LocalAccent.current, style = PrismTypography.titleMedium)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done", color = LocalAccent.current) } },
    )
}

@Composable
fun ConfirmDialog(title: String, text: String, confirmLabel: String = "Delete", onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PrismColors.Slate,
        titleContentColor = PrismColors.TextPrimary,
        textContentColor = PrismColors.TextSecondary,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = { onConfirm(); onDismiss() }) { Text(confirmLabel, color = PrismColors.Bad) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = PrismColors.TextSecondary) } },
    )
}
