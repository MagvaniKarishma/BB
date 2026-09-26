package com.brokerbuddy.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.brokerbuddy.ui.theme.brand

data class BottomItem(val route: String, val label: String, val icon: ImageVector, val selectedIcon: ImageVector)

/**
 * Bottom navigation with a raised microphone button in the centre (quick voice note),
 * two destinations on each side.
 */
@Composable
fun BrandBottomBar(items: List<BottomItem>, selectedRoute: String?, onSelect: (String) -> Unit, onMic: () -> Unit) {
    require(items.size == 4) { "Two items either side of the microphone" }
    val b = MaterialTheme.brand
    Box(Modifier.fillMaxWidth()) {
        Surface(
            modifier = Modifier.fillMaxWidth().padding(top = 22.dp),
            color = b.card,
            shadowElevation = 10.dp,
            shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
        ) {
            Row(Modifier.fillMaxWidth().navigationBarsPadding().height(66.dp), verticalAlignment = Alignment.CenterVertically) {
                items.take(2).forEach { BarItem(it, it.route == selectedRoute, onSelect, Modifier.weight(1f)) }
                Box(Modifier.weight(1f), contentAlignment = Alignment.BottomCenter) {
                    Text("Voice note", style = MaterialTheme.typography.labelSmall, color = b.muted, modifier = Modifier.padding(top = 36.dp))
                }
                items.drop(2).forEach { BarItem(it, it.route == selectedRoute, onSelect, Modifier.weight(1f)) }
            }
        }
        Box(
            Modifier.align(Alignment.TopCenter).offset(y = 2.dp).size(64.dp)
                .shadow(10.dp, CircleShape)
                .clip(CircleShape)
                .background(Brush.verticalGradient(listOf(Color(0xFF3B82F6), Color(0xFF1D4ED8))))
                .clickable(role = Role.Button, onClickLabel = "Record a voice note", onClick = onMic),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Mic, contentDescription = "Record voice note", tint = Color.White, modifier = Modifier.size(30.dp))
        }
    }
}

@Composable
private fun BarItem(item: BottomItem, selected: Boolean, onSelect: (String) -> Unit, modifier: Modifier) {
    val b = MaterialTheme.brand
    val color = if (selected) b.link else b.muted
    Column(
        modifier.clip(RoundedCornerShape(16.dp)).clickable(role = Role.Tab) { onSelect(item.route) }.padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(if (selected) item.selectedIcon else item.icon, contentDescription = null, tint = color, modifier = Modifier.size(26.dp))
        Spacer(Modifier.height(2.dp))
        Text(item.label, style = MaterialTheme.typography.labelMedium, color = color)
    }
}
