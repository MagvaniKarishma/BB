package com.brokerbuddy.ui.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.brokerbuddy.core.model.LoginRequest
import com.brokerbuddy.core.model.RegisterRequest
import com.brokerbuddy.ui.common.appContainer
import com.brokerbuddy.ui.common.rememberText
import kotlinx.coroutines.launch
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.brokerbuddy.R
import com.brokerbuddy.ui.design.BrandWordmark
import com.brokerbuddy.ui.theme.Brand
import com.brokerbuddy.ui.theme.brand
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.text.style.TextAlign
import com.brokerbuddy.core.model.OtpRequest
import com.brokerbuddy.core.model.OtpStatus
import com.brokerbuddy.core.model.OtpVerifyRequest
import com.brokerbuddy.core.phone.PhoneNumbers
import com.brokerbuddy.ui.design.FilterTabs
import com.brokerbuddy.ui.design.TabItem
import kotlinx.coroutines.delay

@Composable
fun LoginScreen() {
    val container = appContainer()
    val scope = rememberCoroutineScope()
    var registering by rememberSaveable { mutableStateOf(false) }
    var server by rememberText()
    var brokerage by rememberText()
    var name by rememberText()
    var email by rememberText()
    var password by rememberText()
    var regMobile by rememberText()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf<String?>(null) }

    // Mobile-number sign-in, offered when the server has SMS set up.
    var otp by remember { mutableStateOf<OtpStatus?>(null) }
    var useMobile by rememberSaveable { mutableStateOf(true) }
    var mobile by rememberText()
    var code by rememberText()
    var sentAt by rememberSaveable { mutableStateOf<Long?>(null) }
    var resendAfter by rememberSaveable { mutableIntStateOf(30) }

    LaunchedEffect(Unit) {
        if (server.isEmpty()) server = container.sessionStore.current().serverUrl
    }
    LaunchedEffect(server) {
        if (server.isBlank()) return@LaunchedEffect
        delay(500) // while typing
        container.sessionStore.setServerUrl(server)
        otp = container.api.call { otpStatus() }.getOrNull()
    }
    val mobileMode = !registering && otp?.enabled == true && useMobile

    fun submit() {
        error = null
        busy = true
        scope.launch {
            container.sessionStore.setServerUrl(server)
            val result = container.api.call {
                if (registering) {
                    register(RegisterRequest(brokerage.trim(), name.trim(), email.trim(), password, indianMobile(regMobile)))
                } else {
                    login(LoginRequest(email.trim(), password))
                }
            }
            result.fold(
                onSuccess = { container.sessionStore.signIn(it.token, it.user) },
                onFailure = { error = it.message },
            )
            busy = false
        }
    }

    fun sendCode() {
        val phone = indianMobile(mobile) ?: run { error = "Enter a 10-digit mobile number"; return }
        error = null
        busy = true
        scope.launch {
            container.sessionStore.setServerUrl(server)
            container.api.call { requestOtp(OtpRequest(phone)) }
                .onSuccess { sent ->
                    resendAfter = sent.retryAfterSec
                    sentAt = System.currentTimeMillis()
                    code = ""
                    info = sent.message
                }
                .onFailure { error = it.message }
            busy = false
        }
    }

    fun verifyCode() {
        val phone = indianMobile(mobile) ?: return
        error = null
        busy = true
        scope.launch {
            container.api.call { verifyOtp(OtpVerifyRequest(phone, code)) }
                .onSuccess { container.sessionStore.signIn(it.token, it.user) }
                .onFailure { error = it.message; code = "" }
            busy = false
        }
    }

    var showServer by rememberSaveable { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.brand.card).systemBarsPadding().imePadding()
            .verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(12.dp))
        BrandWordmark()
        Image(
            painterResource(R.drawable.hero_skyline),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxWidth().height(170.dp).clip(RoundedCornerShape(24.dp)),
        )
        if (!registering && otp?.enabled == true) {
            FilterTabs(
                listOf(TabItem(true, "Mobile number"), TabItem(false, "Email")),
                selected = useMobile,
                onSelect = { useMobile = it; error = null },
            )
        }
        when {
            mobileMode && sentAt == null -> {
                Text("Login with your mobile number", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.brand.navy)
                Text("We'll send you an OTP", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.brand.muted)
                MobileNumberField(mobile, { mobile = it }, enabled = !busy)
                Button(
                    onClick = ::sendCode, enabled = indianMobile(mobile) != null && !busy,
                    colors = ButtonDefaults.buttonColors(containerColor = Brand.Navy),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) { Text(if (busy) "Please wait…" else "Send OTP") }
            }
            mobileMode -> {
                info?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.brand.muted, textAlign = TextAlign.Center) }
                OtpCodeStep(
                    shownNumber = indianMobile(mobile)?.let(PhoneNumbers::display) ?: mobile,
                    code = code, onCode = { code = it }, digits = otp?.digits ?: 6,
                    resendAfterSec = resendAfter, sentAt = sentAt ?: 0L, busy = busy,
                    onVerify = ::verifyCode, onResend = ::sendCode,
                    onChangeNumber = { sentAt = null; code = ""; error = null; info = null },
                )
            }
            else -> {
                Text(
                    if (registering) "Create your brokerage account" else "Sign in to your brokerage",
                    style = MaterialTheme.typography.titleMedium, color = MaterialTheme.brand.navy,
                )
                if (registering) {
                    OutlinedTextField(brokerage, { brokerage = it }, label = { Text("Brokerage name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(name, { name = it }, label = { Text("Your name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
                OutlinedTextField(
                    email, { email = it }, label = { Text("Email") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                )
                OutlinedTextField(
                    password, { password = it }, label = { Text("Password") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    supportingText = if (registering) ({ Text("At least 8 characters") }) else null,
                )
                if (registering) {
                    MobileNumberField(regMobile, { regMobile = it }, enabled = !busy)
                    Text("Optional — lets you sign in with an SMS code later.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.brand.muted)
                }
                val valid = server.isNotBlank() && email.isNotBlank() && password.length >= (if (registering) 8 else 1) &&
                    (!registering || (brokerage.isNotBlank() && name.isNotBlank())) &&
                    (!registering || regMobile.isBlank() || indianMobile(regMobile) != null)
                Button(
                    onClick = ::submit, enabled = valid && !busy,
                    colors = ButtonDefaults.buttonColors(containerColor = Brand.Navy),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) {
                    Text(if (busy) "Please wait…" else if (registering) "Create account" else "Sign in")
                }
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center) }
        if (showServer || server.isBlank()) {
            OutlinedTextField(
                server, { server = it }, label = { Text("Server address") },
                supportingText = { Text("e.g. https://api.yourbrokerage.in") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
        }
        TextButton(onClick = { registering = !registering; error = null }, modifier = Modifier.fillMaxWidth()) {
            Text(if (registering) "Already have an account? Sign in" else "New brokerage? Create an account")
        }
        if (!showServer && server.isNotBlank()) {
            TextButton(onClick = { showServer = true }) {
                Text("Server: ${server.removePrefix("https://").removePrefix("http://")}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.brand.muted)
            }
        }
    }
}
