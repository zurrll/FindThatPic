package dev.localphotos.shitu

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

class MainActivity:ComponentActivity() {
    private val model:PhotoViewModel by viewModels()
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { ShituTheme { PhotoScreen(model) } }
    }
    override fun onResume() { super.onResume();model.onResume() }
}

@Composable private fun ShituTheme(content:@Composable ()->Unit) {
    val colors=if(isSystemInDarkTheme()) darkColorScheme(
        primary=Color(0xFFAEDAC3),onPrimary=Color(0xFF17281F),
        primaryContainer=Color(0xFF263D31),onPrimaryContainer=Color(0xFFD2EADC),
        secondary=Color(0xFFB9C4BE),secondaryContainer=Color(0xFF303A34),onSecondaryContainer=Color(0xFFE0E9E3),
        background=Color(0xFF181C19),onBackground=Color(0xFFE8EFEA),
        surface=Color(0xFF1E2420),onSurface=Color(0xFFE8EFEA),
        surfaceVariant=Color(0xFF252E28),onSurfaceVariant=Color(0xFFB9C4BE),
        outline=Color(0xFF87948B),outlineVariant=Color(0xFF39443E)
    ) else lightColorScheme(
        primary=Color(0xFF205E48),onPrimary=Color.White,
        primaryContainer=Color(0xFFE0EFE5),onPrimaryContainer=Color(0xFF173D2D),
        secondary=Color(0xFF536059),secondaryContainer=Color(0xFFE8EEE9),onSecondaryContainer=Color(0xFF26342B),
        background=Color(0xFFFDFDFB),onBackground=Color(0xFF202823),
        surface=Color(0xFFFFFFFF),onSurface=Color(0xFF202823),
        surfaceVariant=Color(0xFFEDF1EE),onSurfaceVariant=Color(0xFF536059),
        outline=Color(0xFF748178),outlineVariant=Color(0xFFE1E6E2)
    )
    val base=Typography()
    val type=base.copy(
        titleLarge=base.titleLarge.copy(fontSize=20.sp,lineHeight=28.sp,fontWeight=FontWeight.SemiBold),
        titleMedium=base.titleMedium.copy(fontSize=16.sp,lineHeight=23.sp,fontWeight=FontWeight.SemiBold),
        bodyLarge=base.bodyLarge.copy(fontSize=15.sp,lineHeight=22.sp),
        bodyMedium=base.bodyMedium.copy(fontSize=14.sp,lineHeight=21.sp),
        bodySmall=base.bodySmall.copy(fontSize=12.sp,lineHeight=18.sp)
    )
    MaterialTheme(colorScheme=colors,typography=type,content=content)
}
