package com.brokerbuddy.ui.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.brokerbuddy.core.phone.PhoneNumbers
import com.brokerbuddy.ui.theme.brand
import com.brokerbuddy.ui.theme.primaryButtonColors
import kotlinx.coroutines.delay

/** Mobile number with a fixed +91 prefix (Indian numbers); returns what was typed. */
@Composable
fun MobileNumberField(value: String, onChange: (String) -> Unit, enabled: Boolean, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = value,
        onValueChange = { v -> onChange(v.filter { it.isDigit() || it == ' ' }.take(14)) },
        enabled = enabled,
        singleLine = true,
        prefix = { Text("🇮🇳 +91  ") },
        placeholder = { Text("Enter mobile number") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
        modifier = modifier.fillMaxWidth(),
    )
}

/** A 10-digit Indian mobile number as E.164, or null. */
fun indianMobile(typed: String): String? = PhoneNumbers.indianMobile(typed)

/**
 * The code step: 6-digit field, Verify, and Resend (enabled after the server's wait).
 * [sentAt] changes every time a new code is sent, restarting the countdown.
 */
@Composable
fun OtpCodeStep(
    shownNumber: String,
    code: String,
    onCode: (String) -> Unit,
    digits: Int,
    resendAfterSec: Int,
    sentAt: Long,
    busy: Boolean,
    onVerify: () -> Unit,
    onResend: () -> Unit,
    onChangeNumber: () -> Unit,
) {
    var wait by remember(sentAt) { mutableIntStateOf(resendAfterSec) }
    LaunchedEffect(sentAt) {
        while (wait > 0) {
            delay(1000)
            wait--
        }
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Enter the $digits-digit code sent to $shownNumber", style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        OutlinedTextField(
            value = code,
            onValueChange = { v -> onCode(v.filter { it.isDigit() }.take(digits)) },
            enabled = !busy,
            singleLine = true,
            textStyle = TextStyle(fontSize = 26.sp, letterSpacing = 10.sp, textAlign = TextAlign.Center),
            placeholder = { Text("• ".repeat(digits).trim(), modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = onVerify,
            enabled = code.length == digits && !busy,
            colors = primaryButtonColors(),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) { Text(if (busy) "Please wait…" else "Verify") }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onResend, enabled = wait == 0 && !busy) {
                Text(if (wait > 0) "Resend code in ${wait}s" else "Resend code")
            }
            TextButton(onClick = onChangeNumber, enabled = !busy) { Text("Change number", color = MaterialTheme.brand.muted) }
        }
    }
}
