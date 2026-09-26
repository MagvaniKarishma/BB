package com.brokerbuddy.ui.auth

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.brokerbuddy.R

private val NavyTop = Color(0xFF0B1F4B)
private val NavyBottom = Color(0xFF050E26)
private val LogoBlue = Color(0xFF3B82F6)

/** Launch screen (screen 1): shown while the saved session loads. */
@Composable
fun SplashScreen() {
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(NavyTop, NavyBottom)))) {
        // City at night along the bottom: the day artwork, darkened into the navy.
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(0.45f)) {
            Image(
                painterResource(R.drawable.hero_skyline),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alpha = 0.35f,
                modifier = Modifier.fillMaxSize(),
            )
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(NavyTop, Color.Transparent, NavyBottom.copy(alpha = 0.9f)))))
        }
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Image(painterResource(R.drawable.ic_logo_house), contentDescription = null, modifier = Modifier.size(96.dp))
            Spacer(Modifier.height(12.dp))
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(color = Color.White)) { append("Broker") }
                    withStyle(SpanStyle(color = LogoBlue)) { append("Buddy") }
                },
                fontSize = 38.sp,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.headlineLarge,
            )
            Text("Your Real Estate Assistant", color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.bodyLarge)
        }
        Text(
            "Manage Clients\nMatch Properties\nClose More Deals",
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 48.dp),
            color = Color.White,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.titleMedium,
            lineHeight = 26.sp,
        )
    }
}
