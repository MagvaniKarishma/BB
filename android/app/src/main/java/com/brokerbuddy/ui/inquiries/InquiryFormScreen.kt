package com.brokerbuddy.ui.inquiries

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.brokerbuddy.ui.theme.brand
import androidx.compose.material3.TextButton
import androidx.compose.ui.unit.dp
import com.brokerbuddy.core.form.FormField
import com.brokerbuddy.core.form.RequirementForm
import com.brokerbuddy.core.model.TransactionType
import com.brokerbuddy.ui.common.BackTopBar
import com.brokerbuddy.ui.common.appContainer
import kotlinx.coroutines.launch

@Composable
fun InquiryFormScreen(clientId: String?, inquiryId: String?, onBack: () -> Unit, onSaved: (String) -> Unit) {
    val api = appContainer().api
    val scope = rememberCoroutineScope()
    var form by rememberSaveable(stateSaver = RequirementFormSaver) {
        mutableStateOf(RequirementForm(transactionType = TransactionType.RENT))
    }
    var loaded by rememberSaveable { mutableStateOf(inquiryId == null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // Words each voice-filled field came from (shown under the field).
    var evidence by remember { mutableStateOf<Map<FormField, String>>(emptyMap()) }

    LaunchedEffect(inquiryId) {
        if (inquiryId == null || loaded) return@LaunchedEffect
        api.call { inquiry(inquiryId).inquiry }
            .onSuccess { form = RequirementForm.fromInquiry(it); loaded = true }
            .onFailure { error = it.message }
    }

    val validation = form.validate()
    // Required-field errors are shown only once the agent tries to save.
    var attempted by rememberSaveable { mutableStateOf(false) }
    val shownErrors = if (attempted) validation.errors else validation.errors.filterKeys { it != FormField.TRANSACTION && it != FormField.CATEGORY }

    fun save() {
        attempted = true
        val body = validation.request ?: return
        error = null
        busy = true
        scope.launch {
            val result = if (inquiryId == null) {
                api.call { createInquiry(clientId!!, body).inquiry }
            } else {
                api.call { updateInquiry(inquiryId, body).inquiry }
            }
            result.onSuccess { onSaved(it.id) }.onFailure { error = it.message }
            busy = false
        }
    }

    Scaffold(
        containerColor = MaterialTheme.brand.background,
        topBar = {
            BackTopBar(if (inquiryId == null) "Add Requirement" else "Edit Requirement", onBack) {
                TextButton(onClick = ::save, enabled = loaded && !busy) { Text("Save") }
            }
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (loaded) {
                VoiceFillCard(
                    form = form,
                    onFilled = { filled, words -> form = filled; evidence = evidence + words },
                    onUndo = { form = it; evidence = emptyMap() },
                )
            }
            Text(
                "Or fill it in by hand. Leave anything the client hasn't said blank — BrokerBuddy never fills in guesses.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            RequirementEditor(form, { form = it }, shownErrors, showStatus = inquiryId != null, evidence = evidence)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(onClick = ::save, enabled = loaded && !busy, modifier = Modifier.fillMaxWidth()) {
                Text(if (busy) "Saving…" else "Save requirement")
            }
        }
    }
}
