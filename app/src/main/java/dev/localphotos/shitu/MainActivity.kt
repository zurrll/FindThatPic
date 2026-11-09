package dev.localphotos.shitu

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable

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
    val colors=if(isSystemInDarkTheme()) darkColorScheme(primary=androidx.compose.ui.graphics.Color(0xFF9FD8C3),onPrimary=androidx.compose.ui.graphics.Color(0xFF00382D),primaryContainer=androidx.compose.ui.graphics.Color(0xFF244337),onPrimaryContainer=androidx.compose.ui.graphics.Color(0xFFC2EBD8),secondaryContainer=androidx.compose.ui.graphics.Color(0xFF304C3E),onSecondaryContainer=androidx.compose.ui.graphics.Color(0xFFD2EADC),surfaceVariant=androidx.compose.ui.graphics.Color(0xFF293B32),onSurfaceVariant=androidx.compose.ui.graphics.Color(0xFFB9CBBF),background=androidx.compose.ui.graphics.Color(0xFF111916),surface=androidx.compose.ui.graphics.Color(0xFF19231F))
    else lightColorScheme(primary=androidx.compose.ui.graphics.Color(0xFF176B5B),primaryContainer=androidx.compose.ui.graphics.Color(0xFFE0EFE9),onPrimaryContainer=androidx.compose.ui.graphics.Color(0xFF163F33),secondary=androidx.compose.ui.graphics.Color(0xFF516454),secondaryContainer=androidx.compose.ui.graphics.Color(0xFFDFEEDD),onSecondaryContainer=androidx.compose.ui.graphics.Color(0xFF10372E),background=androidx.compose.ui.graphics.Color(0xFFF4F6F5),surface=androidx.compose.ui.graphics.Color(0xFFFFFFFF),surfaceVariant=androidx.compose.ui.graphics.Color(0xFFE8EDE4))
    MaterialTheme(colorScheme=colors,content=content)
}
