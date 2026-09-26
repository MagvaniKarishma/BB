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
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        if (server.isEmpty()) server = container.sessionStore.current().serverUrl
    }

    fun submit() {
        error = null
        busy = true
        scope.launch {
            container.sessionStore.setServerUrl(server)
            val result = container.api.call {
                if (registering) {
                    register(RegisterRequest(brokerage.trim(), name.trim(), email.trim(), password))
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

    Column(
        Modifier.fillMaxSize().systemBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("BrokerBuddy", style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.primary)
        Text(
            if (registering) "Create your brokerage account" else "Sign in to your brokerage",
            style = MaterialTheme.typography.bodyLarge,
        )
        OutlinedTextField(
            server, { server = it }, label = { Text("Server address") },
            supportingText = { Text("e.g. https://api.yourbrokerage.in") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
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
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        val valid = email.isNotBlank() && password.length >= (if (registering) 8 else 1) &&
            (!registering || (brokerage.isNotBlank() && name.isNotBlank()))
        Button(onClick = ::submit, enabled = valid && !busy, modifier = Modifier.fillMaxWidth()) {
            Text(if (busy) "Please wait…" else if (registering) "Create account" else "Sign in")
        }
        TextButton(onClick = { registering = !registering; error = null }, modifier = Modifier.fillMaxWidth()) {
            Text(if (registering) "Already have an account? Sign in" else "New brokerage? Create an account")
        }
    }
}
