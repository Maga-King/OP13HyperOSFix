package local.mio.op13hyperosfix

import android.app.Activity
import android.graphics.Color as AndroidColor
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import kotlin.math.roundToInt

private val FixShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(6.dp),
    medium = RoundedCornerShape(8.dp),
    large = RoundedCornerShape(8.dp),
    extraLarge = RoundedCornerShape(8.dp),
)

private val FixLightColors = lightColorScheme(
    primary = Color(0xFF2563EB),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDBEAFE),
    onPrimaryContainer = Color(0xFF153E8F),
    secondary = Color(0xFF16834B),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD9F5E5),
    onSecondaryContainer = Color(0xFF09522C),
    tertiary = Color(0xFFAD6100),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFE6BC),
    onTertiaryContainer = Color(0xFF683900),
    error = Color(0xFFB93E4A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDADD),
    onErrorContainer = Color(0xFF7A1F2A),
    background = Color(0xFFF5F7F8),
    onBackground = Color(0xFF171A1C),
    surface = Color(0xFFF5F7F8),
    onSurface = Color(0xFF171A1C),
    surfaceVariant = Color(0xFFE8ECEF),
    onSurfaceVariant = Color(0xFF596167),
    outline = Color(0xFF747C82),
    outlineVariant = Color(0xFFDCE2E5),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFAFBFC),
    surfaceContainer = Color.White,
    surfaceContainerHigh = Color(0xFFEEF2F4),
    surfaceContainerHighest = Color(0xFFE6EBEE),
)

private val FixDarkColors = darkColorScheme(
    primary = Color(0xFF79B5FF),
    onPrimary = Color(0xFF002B5E),
    primaryContainer = Color(0xFF163D6B),
    onPrimaryContainer = Color(0xFFD6E8FF),
    secondary = Color(0xFF69D59A),
    onSecondary = Color(0xFF00391F),
    secondaryContainer = Color(0xFF174C31),
    onSecondaryContainer = Color(0xFFD0F7DF),
    tertiary = Color(0xFFF2C46E),
    onTertiary = Color(0xFF432B00),
    tertiaryContainer = Color(0xFF5D410D),
    onTertiaryContainer = Color(0xFFFFE7B5),
    error = Color(0xFFFFB2B8),
    onError = Color(0xFF680018),
    errorContainer = Color(0xFF6B2832),
    onErrorContainer = Color(0xFFFFDADD),
    background = Color(0xFF101314),
    onBackground = Color(0xFFE7EBED),
    surface = Color(0xFF101314),
    onSurface = Color(0xFFE7EBED),
    surfaceVariant = Color(0xFF2A3033),
    onSurfaceVariant = Color(0xFFB5BDC1),
    outline = Color(0xFF8B9499),
    outlineVariant = Color(0xFF343A3D),
    surfaceContainerLowest = Color(0xFF0C0E0F),
    surfaceContainerLow = Color(0xFF141718),
    surfaceContainer = Color(0xFF191D1F),
    surfaceContainerHigh = Color(0xFF202527),
    surfaceContainerHighest = Color(0xFF293033),
)

private val FixTypography = Typography(
    headlineMedium = TextStyle(
        fontSize = 28.sp,
        lineHeight = 34.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.sp,
    ),
    headlineSmall = TextStyle(
        fontSize = 24.sp,
        lineHeight = 30.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.sp,
    ),
    titleLarge = TextStyle(
        fontSize = 21.sp,
        lineHeight = 27.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.sp,
    ),
    titleMedium = TextStyle(
        fontSize = 16.sp,
        lineHeight = 22.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.sp,
    ),
    titleSmall = TextStyle(
        fontSize = 14.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.sp,
    ),
    bodyLarge = TextStyle(
        fontSize = 16.sp,
        lineHeight = 23.sp,
        letterSpacing = 0.sp,
    ),
    bodyMedium = TextStyle(
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.sp,
    ),
    bodySmall = TextStyle(
        fontSize = 12.sp,
        lineHeight = 17.sp,
        letterSpacing = 0.sp,
    ),
    labelLarge = TextStyle(
        fontSize = 14.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.sp,
    ),
    labelMedium = TextStyle(
        fontSize = 12.sp,
        lineHeight = 17.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.sp,
    ),
)

@Composable
internal fun Op13FixTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val colors = if (dark) FixDarkColors else FixLightColors
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val activity = view.context as? Activity ?: return@SideEffect
            activity.window.statusBarColor = AndroidColor.TRANSPARENT
            activity.window.navigationBarColor = AndroidColor.TRANSPARENT
            activity.window.isNavigationBarContrastEnforced = false
            WindowCompat.getInsetsController(activity.window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    MaterialTheme(
        colorScheme = colors,
        shapes = FixShapes,
        typography = FixTypography,
        content = content,
    )
}

internal data class FeatureItem(
    val title: String,
    val summary: String,
    val icon: ImageVector,
    val tone: FeatureTone = FeatureTone.Blue,
)

internal enum class FeatureTone {
    Blue,
    Green,
    Amber,
    Red,
}

@Composable
private fun toneColor(tone: FeatureTone): Color = when (tone) {
    FeatureTone.Blue -> MaterialTheme.colorScheme.primary
    FeatureTone.Green -> MaterialTheme.colorScheme.secondary
    FeatureTone.Amber -> MaterialTheme.colorScheme.tertiary
    FeatureTone.Red -> MaterialTheme.colorScheme.error
}

@Composable
internal fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    tone: FeatureTone = FeatureTone.Blue,
) {
    Row(
        modifier = modifier.padding(start = 4.dp, top = 10.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(width = 4.dp, height = 15.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(toneColor(tone)),
        )
        Spacer(Modifier.width(9.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun StatusPill(text: String, positive: Boolean, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = if (positive) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.errorContainer
        },
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = if (positive) {
                MaterialTheme.colorScheme.onSecondaryContainer
            } else {
                MaterialTheme.colorScheme.onErrorContainer
            },
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            maxLines = 1,
        )
    }
}

@Composable
internal fun SettingsGroup(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(content = content)
    }
}

@Composable
internal fun InsetDivider() {
    androidx.compose.material3.HorizontalDivider(
        modifier = Modifier.padding(start = 66.dp, end = 14.dp),
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

@Composable
internal fun SettingSwitchRow(
    title: String,
    summary: String,
    icon: ImageVector,
    checked: Boolean,
    enabled: Boolean,
    tone: FeatureTone = FeatureTone.Blue,
    grouped: Boolean = false,
    onCheckedChange: (Boolean) -> Unit,
) {
    val accent = toneColor(tone)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (grouped) Modifier else Modifier.clip(MaterialTheme.shapes.medium))
            .clickable(enabled = enabled) { onCheckedChange(!checked) },
        shape = if (grouped) RoundedCornerShape(0.dp) else MaterialTheme.shapes.medium,
        color = if (grouped) {
            Color.Transparent
        } else if (checked) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.62f)
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(accent.copy(alpha = if (enabled) 0.14f else 0.07f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (enabled) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(21.dp),
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(12.dp))
            Switch(
                checked = checked,
                enabled = enabled,
                onCheckedChange = onCheckedChange,
                thumbContent = {
                    Icon(
                        imageVector = if (checked) Icons.Rounded.Check else Icons.Rounded.Close,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp),
                    )
                },
            )
        }
    }
}

@Composable
internal fun SettingSliderRow(
    title: String,
    summary: String,
    icon: ImageVector,
    value: Int,
    values: List<Int>,
    suffix: String = "Hz",
    enabled: Boolean,
    tone: FeatureTone = FeatureTone.Blue,
    grouped: Boolean = false,
    onValueCommitted: (Int) -> Unit,
) {
    if (values.isEmpty()) return
    val accent = toneColor(tone)
    val selectedIndex = values.indexOf(value).let { if (it >= 0) it else 0 }
    var sliderPosition by remember(value, values) {
        mutableFloatStateOf(selectedIndex.toFloat())
    }
    val previewIndex = sliderPosition.roundToInt().coerceIn(values.indices)

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (grouped) Modifier else Modifier.clip(MaterialTheme.shapes.medium)),
        shape = if (grouped) RoundedCornerShape(0.dp) else MaterialTheme.shapes.medium,
        color = if (grouped) Color.Transparent else MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(accent.copy(alpha = if (enabled) 0.14f else 0.07f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (enabled) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(21.dp),
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${values[previewIndex]} $suffix",
                        style = MaterialTheme.typography.labelLarge,
                        color = accent,
                    )
                }
                Spacer(Modifier.height(3.dp))
                Text(
                    summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Slider(
                    value = sliderPosition,
                    onValueChange = { sliderPosition = it },
                    onValueChangeFinished = {
                        val selected = values[sliderPosition.roundToInt()
                            .coerceIn(values.indices)]
                        if (selected != value) onValueCommitted(selected)
                    },
                    enabled = enabled,
                    valueRange = 0f..values.lastIndex.toFloat(),
                    steps = (values.size - 2).coerceAtLeast(0),
                )
            }
        }
    }
}

@Composable
internal fun FeatureGrid(items: List<FeatureItem>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.chunked(2).forEach { pair ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                pair.forEach { item ->
                    FeatureTile(item, Modifier.weight(1f))
                }
                if (pair.size == 1) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun FeatureTile(item: FeatureItem, modifier: Modifier = Modifier) {
    val accent = toneColor(item.tone)
    Surface(
        modifier = modifier.height(128.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = accent.copy(alpha = 0.14f),
            ) {
                Box(
                    modifier = Modifier.size(34.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = item.icon,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = item.summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
internal fun NavigationRow(
    title: String,
    summary: String,
    icon: ImageVector,
    tone: FeatureTone = FeatureTone.Blue,
    grouped: Boolean = false,
    trailing: @Composable (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val accent = toneColor(tone)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (grouped) Modifier else Modifier.clip(MaterialTheme.shapes.medium))
            .clickable(onClick = onClick),
        shape = if (grouped) RoundedCornerShape(0.dp) else MaterialTheme.shapes.medium,
        color = if (grouped) Color.Transparent else MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 15.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(accent.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(21.dp),
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(3.dp))
                Text(
                    summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(12.dp))
            trailing?.invoke()
        }
    }
}
