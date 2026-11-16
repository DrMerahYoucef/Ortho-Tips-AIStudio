package com.example.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// Fallback color scheme for devices below Android 12 (API < 31)
private val FallbackDarkColorScheme = darkColorScheme(
    primary = OrthoPrimaryDark,
    onPrimary = OrthoOnPrimaryDark,
    primaryContainer = OrthoPrimaryContainerDark,
    onPrimaryContainer = OrthoOnPrimaryContainerDark,
    secondary = OrthoSecondaryDark,
    onSecondary = OrthoOnSecondaryDark,
    secondaryContainer = OrthoSecondaryContainerDark,
    onSecondaryContainer = OrthoOnSecondaryContainerDark,
    tertiary = OrthoTertiaryDark,
    onTertiary = OrthoOnTertiaryDark,
    tertiaryContainer = OrthoTertiaryContainerDark,
    onTertiaryContainer = OrthoOnTertiaryContainerDark,
    background = OrthoBackgroundDark,
    onBackground = OrthoOnBackgroundDark,
    surface = OrthoSurfaceDark,
    onSurface = OrthoOnSurfaceDark,
    surfaceVariant = OrthoSurfaceVariantDark,
    onSurfaceVariant = OrthoOnSurfaceVariantDark,
    outline = OrthoOutlineDark
)

// Fallback color scheme for devices below Android 12 (API < 31)
private val FallbackLightColorScheme = lightColorScheme(
    primary = OrthoPrimaryLight,
    onPrimary = OrthoOnPrimaryLight,
    primaryContainer = OrthoPrimaryContainerLight,
    onPrimaryContainer = OrthoOnPrimaryContainerLight,
    secondary = OrthoSecondaryLight,
    onSecondary = OrthoOnSecondaryLight,
    secondaryContainer = OrthoSecondaryContainerLight,
    onSecondaryContainer = OrthoOnSecondaryContainerLight,
    tertiary = OrthoTertiaryLight,
    onTertiary = OrthoOnTertiaryLight,
    tertiaryContainer = OrthoTertiaryContainerLight,
    onTertiaryContainer = OrthoOnTertiaryContainerLight,
    background = OrthoBackgroundLight,
    onBackground = OrthoOnBackgroundLight,
    surface = OrthoSurfaceLight,
    onSurface = OrthoOnSurfaceLight,
    surfaceVariant = OrthoSurfaceVariantLight,
    onSurfaceVariant = OrthoOnSurfaceVariantLight,
    outline = OrthoOutlineLight
)

@Composable
fun OrthoTipsTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true, // Material You dynamic colors enabled by default
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) {
                dynamicDarkColorScheme(context)
            } else {
                dynamicLightColorScheme(context)
            }
        }
        darkTheme -> FallbackDarkColorScheme
        else -> FallbackLightColorScheme
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                // Bars stay transparent (edge-to-edge); only their icon colour follows
                // the app's own theme, which can differ from the system's.
                val insetsController = WindowCompat.getInsetsController(window, view)
                insetsController.isAppearanceLightStatusBars = !darkTheme
                insetsController.isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}

private val isDarkScheme: Boolean
    @Composable get() = MaterialTheme.colorScheme.background.luminance() < 0.5f

/** Semantic colours Material 3 does not provide, resolved for light or dark. */
object OrthoColors {
    val attended: Color
        @Composable get() = if (isDarkScheme) AttendedDark else AttendedLight
    val attendedContainer: Color
        @Composable get() = if (isDarkScheme) AttendedContainerDark else AttendedContainerLight
    val onAttendedContainer: Color
        @Composable get() = if (isDarkScheme) OnAttendedContainerDark else OnAttendedContainerLight
}
