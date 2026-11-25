package com.ilainalam.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val Leaf = Color(0xFF2E7D32)
val LeafDark = Color(0xFF1B5E20)
val LeafLight = Color(0xFFE8F5E9)
val Soil = Color(0xFF5D4037)
val Paper = Color(0xFFF7FBF4)

// Severity colours are always shown together with an icon and a word (never colour alone)
val SevMild = Color(0xFF2E7D32)
val SevModerate = Color(0xFFE65100)
val SevSevere = Color(0xFFB71C1C)

private val Colors = lightColorScheme(
    primary = Leaf, onPrimary = Color.White,
    primaryContainer = LeafLight, onPrimaryContainer = LeafDark,
    secondary = Soil, onSecondary = Color.White,
    background = Paper, onBackground = Color(0xFF1A1C19),
    surface = Color.White, onSurface = Color(0xFF1A1C19),
    surfaceVariant = Color(0xFFEFF3EC), onSurfaceVariant = Color(0xFF3F4A3C),
    error = SevSevere,
)

// Larger-than-default text for outdoor reading; the phone's font-size setting still scales it.
private val Type = Typography(
    headlineSmall = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Bold, lineHeight = 32.sp),
    titleLarge = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold, lineHeight = 28.sp),
    titleMedium = TextStyle(fontSize = 19.sp, fontWeight = FontWeight.SemiBold, lineHeight = 25.sp),
    bodyLarge = TextStyle(fontSize = 18.sp, lineHeight = 26.sp),
    bodyMedium = TextStyle(fontSize = 16.sp, lineHeight = 23.sp),
    labelLarge = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold),
)

@Composable
fun IlaiNalamTheme(content: @Composable () -> Unit) =
    MaterialTheme(colorScheme = Colors, typography = Type, content = content)
