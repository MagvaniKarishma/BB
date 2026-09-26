@file:OptIn(ExperimentalMaterial3Api::class)

package com.brokerbuddy.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.PhoneInTalk
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.brokerbuddy.ui.design.BrandWordmark
import com.brokerbuddy.ui.theme.brand

/** The ☰ menu: everything that isn't a bottom-bar destination. */
@Composable
fun MoreMenuSheet(onDismiss: () -> Unit, onNavigate: (String) -> Unit, onSignOut: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.brand.card,
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 12.dp)) {
            BrandWordmark(Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            MenuRow(Icons.Outlined.Forum, "WhatsApp & portal leads", "New enquiries and messages to review") { onNavigate("whatsapp/inbox") }
            MenuRow(Icons.Outlined.UploadFile, "Import from WhatsApp", "Paste a message or an exported chat") { onNavigate("whatsapp/import") }
            MenuRow(Icons.Outlined.PhoneInTalk, "Caller screen", "Set up caller identification") { onNavigate(Routes.SETTINGS) }
            MenuRow(Icons.Outlined.Settings, "Settings & team", "Team members, WhatsApp connection, account") { onNavigate(Routes.SETTINGS) }
            MenuRow(Icons.AutoMirrored.Outlined.Logout, "Sign out", null, onSignOut)
        }
    }
}

@Composable
private fun MenuRow(icon: ImageVector, title: String, subtitle: String?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = MaterialTheme.brand.link, modifier = Modifier.size(24.dp))
        Column(Modifier.padding(start = 16.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.brand.navy)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.brand.muted) }
        }
    }
}
