package com.brokerbuddy.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.brokerbuddy.core.model.OtpRequest
import com.brokerbuddy.core.model.OtpVerifyRequest
import com.brokerbuddy.core.phone.PhoneNumbers
import com.brokerbuddy.ui.auth.MobileNumberField
import com.brokerbuddy.ui.auth.OtpCodeStep
import com.brokerbuddy.ui.auth.indianMobile
import com.brokerbuddy.ui.common.SectionTitle
import com.brokerbuddy.ui.common.appContainer
import com.brokerbuddy.ui.common.rememberLoad
import com.brokerbuddy.ui.common.toast
import com.brokerbuddy.ui.theme.brand
import kotlinx.coroutines.launch

/** Add or change your own mobile number (for SMS sign-in); saved only after its code is entered. */
@Composable
fun MyMobileSection(current: String?, onChanged: () -> Unit) {
    val api = appContainer().api
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val status = rememberLoad { api.call { otpStatus() } }
    var editing by remember { mutableStateOf(false) }
    var mobile by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var sentAt by remember { mutableStateOf<Long?>(null) }
    var resendAfter by remember { mutableIntStateOf(30) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val enabled = (status.state as? com.brokerbuddy.ui.common.Load.Ready)?.value?.enabled == true

    fun send() {
        val phone = indianMobile(mobile) ?: return
        busy = true; error = null
        scope.launch {
            api.call { requestPhoneOtp(OtpRequest(phone)) }
                .onSuccess { resendAfter = it.retryAfterSec; sentAt = System.currentTimeMillis(); code = "" }
                .onFailure { error = it.message }
            busy = false
        }
    }

    fun verify() {
        val phone = indianMobile(mobile) ?: return
        busy = true; error = null
        scope.launch {
            api.call { verifyPhoneOtp(OtpVerifyRequest(phone, code)) }
                .onSuccess { toast(context, "Mobile number saved"); editing = false; sentAt = null; onChanged() }
                .onFailure { error = it.message; code = "" }
            busy = false
        }
    }

    SectionTitle("Your mobile number")
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(current?.let { PhoneNumbers.display(it) } ?: "Not added", style = MaterialTheme.typography.bodyLarge)
        when {
            !enabled -> Text(
                "Sign-in with an SMS code isn't set up on this server yet.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.brand.muted,
            )
            !editing -> OutlinedButton(onClick = { editing = true }) { Text(if (current == null) "Add mobile number" else "Change number") }
            sentAt == null -> {
                Text("We'll send a code to confirm it's yours.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.brand.muted)
                MobileNumberField(mobile, { mobile = it }, enabled = !busy)
                OutlinedButton(onClick = ::send, enabled = indianMobile(mobile) != null && !busy, modifier = Modifier.fillMaxWidth()) { Text("Send code") }
                TextButton(onClick = { editing = false; error = null }) { Text("Cancel") }
            }
            else -> OtpCodeStep(
                shownNumber = indianMobile(mobile)?.let(PhoneNumbers::display) ?: mobile,
                code = code, onCode = { code = it }, digits = 6, resendAfterSec = resendAfter, sentAt = sentAt ?: 0L, busy = busy,
                onVerify = ::verify, onResend = ::send, onChangeNumber = { sentAt = null; code = "" },
            )
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}
