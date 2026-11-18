package app.brujula.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.brujula.core.theme.BrujulaTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            BrujulaTheme(dark = isSystemInDarkTheme()) {
                Box(
                    modifier = Modifier.fillMaxSize().background(BrujulaTheme.colors.bg),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "Brújula",
                        color = BrujulaTheme.colors.text,
                        fontFamily = BrujulaTheme.type.display,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 40.sp,
                    )
                }
            }
        }
    }
}
