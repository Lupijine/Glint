package app.glint.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.glint.ui.theme.glassSurface
import app.glint.ui.theme.liquidGlass

val SecondaryText = Color(0xFFCAD0DC)

/** The large white title at the top of each page, with optional actions at its end. */
@Composable
fun LargeTitle(
    title: String,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(start = 24.dp, end = 16.dp, top = 24.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            modifier = Modifier.weight(1f),
            style =
                MaterialTheme.typography.headlineLarge.copy(
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 34.sp,
                    letterSpacing = (-0.9).sp,
                ),
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        actions()
    }
}

/** A glass segmented control. */
@Composable
fun <T> GlassSegmented(
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    height: Int = 52,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .height(height.dp)
                .liquidGlass(RoundedCornerShape((height / 2).dp))
                .padding(4.dp)
                .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEach { option ->
            val isSelected = option == selected
            val container by
                animateColorAsState(
                    if (isSelected) Color.White.copy(alpha = 0.14f) else Color.Transparent,
                    label = "segment",
                )
            val rim by animateColorAsState(
                if (isSelected) Color.White.copy(alpha = 0.16f) else Color.Transparent,
                label = "segment rim",
            )
            val content by animateColorAsState(
                if (isSelected) Color.White else SecondaryText,
                label = "segment content",
            )
            Box(
                modifier =
                    Modifier.weight(1f)
                        .fillMaxHeight()
                        .clip(CircleShape)
                        .background(
                            Brush.verticalGradient(listOf(container, container.copy(alpha = container.alpha * 0.3f))))
                        .border(0.5.dp, rim, CircleShape)
                        .selectable(
                            selected = isSelected,
                            role = Role.Tab,
                            onClick = { onSelect(option) },
                        ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label(option),
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.Medium,
                    color = content,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** A glass card explaining an empty page. */
@Composable
fun EmptyCard(
    icon: ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .glassSurface(RoundedCornerShape(28.dp))
                .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier.size(64.dp).glassSurface(RoundedCornerShape(22.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(30.dp),
            )
        }
        Spacer(Modifier.height(16.dp))
        Text(
            text = title,
            color = Color.White,
            fontSize = 17.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = body,
            color = SecondaryText,
            fontSize = 14.sp,
            lineHeight = 21.sp,
            textAlign = TextAlign.Center,
        )
    }
}


/** A small label above a group of controls. */
@Composable
fun FieldLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier.padding(start = 4.dp),
        color = SecondaryText,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
    )
}

/** A row of glass chips, one of which is selected. It scrolls when the chips do not fit. */
@Composable
fun <T> ChoiceRow(
    options: List<T>,
    selected: T?,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    detail: ((T) -> String?)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { option ->
            val isSelected = option == selected
            val fill by animateColorAsState(
                if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else Color.Transparent,
                label = "choice fill",
            )
            val secondLine = detail?.invoke(option)
            Column(
                modifier =
                    Modifier.height(if (secondLine != null) 52.dp else 44.dp)
                        .glassSurface(
                            RoundedCornerShape(if (secondLine != null) 18.dp else 22.dp),
                            tint = fill,
                        )
                        .selectable(selected = isSelected, role = Role.RadioButton) {
                            onSelect(option)
                        }
                        .padding(horizontal = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = label(option),
                    color = if (isSelected) Color.White else SecondaryText,
                    fontSize = 14.sp,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                    maxLines = 1,
                )
                if (secondLine != null) {
                    Text(
                        text = secondLine,
                        color = SecondaryText.copy(alpha = if (isSelected) 0.95f else 0.7f),
                        fontSize = 11.5.sp,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** A small glass pill for secondary actions. */
@Composable
fun GlassPill(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    tint: Color = Color.White,
) {
    Row(
        modifier =
            modifier
                .height(44.dp)
                .glassSurface(CircleShape)
                .clickable(role = Role.Button, onClick = onClick)
                .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(7.dp))
        }
        Text(
            text = text,
            color = tint,
            fontSize = 13.5.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** A round glass button holding one icon. */
@Composable
fun GlassIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = SecondaryText,
    size: Int = 46,
) {
    Box(
        modifier =
            modifier
                .size(size.dp)
                .glassSurface(CircleShape)
                .clickable(onClickLabel = contentDescription, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = contentDescription, tint = tint, modifier = Modifier.size(21.dp))
    }
}

/** A titled group of settings on one glass card. */
@Composable
fun SettingsSection(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            modifier = Modifier.padding(start = 8.dp),
            color = SecondaryText,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Column(
            modifier =
                Modifier.fillMaxWidth()
                    .glassSurface(RoundedCornerShape(26.dp))
                    .padding(vertical = 8.dp),
        ) {
            content()
        }
    }
}

/** A setting's name and an optional explanation. */
@Composable
fun SettingText(title: String, subtitle: String?, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(text = title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        if (subtitle != null) {
            Spacer(Modifier.height(2.dp))
            Text(text = subtitle, color = SecondaryText, fontSize = 12.5.sp, lineHeight = 17.sp)
        }
    }
}

/** A setting chosen from a few options shown as chips. */
@Composable
fun <T> ChoiceSetting(
    title: String,
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    subtitle: String? = null,
    detail: ((T) -> String?)? = null,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SettingText(title, subtitle, Modifier.padding(horizontal = 18.dp))
        ChoiceRow(
            options = options,
            selected = selected,
            label = label,
            onSelect = onSelect,
            detail = detail,
            modifier = Modifier.padding(horizontal = 14.dp),
        )
    }
}

/** A setting turned on or off; the whole row is the switch. */
@Composable
fun SwitchSetting(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    subtitle: String? = null,
) {
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
                .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SettingText(title, subtitle, Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = null,
            colors =
                SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.55f),
                    checkedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.40f),
                    uncheckedThumbColor = Color.White.copy(alpha = 0.80f),
                    uncheckedTrackColor = Color.White.copy(alpha = 0.08f),
                    uncheckedBorderColor = Color.White.copy(alpha = 0.14f),
                ),
        )
    }
}

/** A glass text field. [monospace] suits command lines and paths. */
@Composable
fun GlassTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    monospace: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    isError: Boolean = false,
) {
    val shape = RoundedCornerShape(18.dp)
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .glassSurface(shape)
                .border(1.dp, if (isError) MaterialTheme.colorScheme.error else Color.Transparent, shape)
                .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        val family = if (monospace) FontFamily.Monospace else FontFamily.Default
        if (value.isEmpty()) {
            Text(text = placeholder, color = SecondaryText.copy(alpha = 0.6f), fontSize = 14.5.sp, fontFamily = family)
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = singleLine,
            minLines = if (singleLine) 1 else 3,
            textStyle = TextStyle(color = Color.White, fontSize = 14.5.sp, fontFamily = family, lineHeight = 20.sp),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType, autoCorrectEnabled = false),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
