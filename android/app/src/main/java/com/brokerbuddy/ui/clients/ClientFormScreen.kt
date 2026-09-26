package com.brokerbuddy.ui.clients

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.unit.dp
import com.brokerbuddy.core.model.ClientRef
import com.brokerbuddy.core.model.ClientStatus
import com.brokerbuddy.core.model.CreateClientRequest
import com.brokerbuddy.core.model.LeadSource
import com.brokerbuddy.core.model.UpdateClientRequest
import com.brokerbuddy.core.phone.PhoneNumbers
import com.brokerbuddy.data.ApiException
import com.brokerbuddy.ui.common.BackTopBar
import com.brokerbuddy.ui.common.DropdownField
import com.brokerbuddy.ui.common.appContainer
import com.brokerbuddy.ui.common.rememberText
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Create (clientId == null) or edit a client. Phone numbers are fixed after creation. */
@Composable
fun ClientFormScreen(
    clientId: String?,
    onBack: () -> Unit,
    onSaved: (String) -> Unit,
    onOpenExisting: (String) -> Unit,
) {
    val api = appContainer().api
    val scope = rememberCoroutineScope()
    var name by rememberText()
    var phone by rememberText()
    var altPhone by rememberText()
    var email by rememberText()
    var notes by rememberText()
    var leadSource by rememberSaveable { mutableStateOf<LeadSource?>(null) }
    var status by rememberSaveable { mutableStateOf(ClientStatus.NEW) }
    var loaded by rememberSaveable { mutableStateOf(clientId == null) }
    var duplicate by remember { mutableStateOf<ClientRef?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(clientId) {
        if (clientId != null && !loaded) {
            api.call { client(clientId) }.onSuccess { (c) ->
                name = c.name; email = c.email ?: ""; notes = c.notes ?: ""
                leadSource = c.leadSource; status = c.status; phone = c.primaryPhone
                loaded = true
            }.onFailure { error = it.message }
        }
    }

    // Live duplicate check while typing a new client's number.
    LaunchedEffect(phone) {
        duplicate = null
        if (clientId != null) return@LaunchedEffect
        val normalized = PhoneNumbers.normalize(phone) ?: return@LaunchedEffect
        delay(400)
        api.call { checkDuplicate(normalized) }.onSuccess { duplicate = it.duplicate }
    }

    fun save() {
        error = null
        busy = true
        scope.launch {
            val source = leadSource ?: return@launch
            if (clientId == null) {
                api.call {
                    createClient(
                        CreateClientRequest(
                            name = name.trim(),
                            phone = phone.trim(),
                            altPhones = listOf(altPhone.trim()).filter { it.isNotEmpty() },
                            email = email.trim().ifEmpty { null },
                            leadSource = source,
                            status = status,
                            notes = notes.trim().ifEmpty { null },
                        ),
                    )
                }.onSuccess { onSaved(it.client.id) }.onFailure { e ->
                    val existing = (e as? ApiException)?.takeIf { it.code == "DUPLICATE_CLIENT" }?.existingClient()
                    if (existing != null) duplicate = existing else error = e.message
                }
            } else {
                api.call {
                    updateClient(
                        clientId,
                        UpdateClientRequest(
                            name = name.trim(),
                            email = email.trim().ifEmpty { null },
                            leadSource = source,
                            status = status,
                            notes = notes.trim().ifEmpty { null },
                        ),
                    )
                }.onSuccess { onSaved(it.client.id) }.onFailure { error = it.message }
            }
            busy = false
        }
    }

    Scaffold(topBar = { BackTopBar(if (clientId == null) "New client" else "Edit client", onBack) }) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(name, { name = it }, label = { Text("Name *") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                phone, { phone = it }, label = { Text("Phone *") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                enabled = clientId == null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                isError = phone.isNotBlank() && PhoneNumbers.normalize(phone) == null,
                supportingText = {
                    val n = PhoneNumbers.normalize(phone)
                    Text(if (phone.isBlank()) "Indian numbers can be typed without +91" else n?.let { PhoneNumbers.display(it) } ?: "Not a valid phone number")
                },
            )
            duplicate?.let { dup ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Column(Modifier.padding(12.dp)) {
                        Text("This number already belongs to ${dup.name}", style = MaterialTheme.typography.titleSmall)
                        Text("Add a new inquiry to the existing profile instead of creating a duplicate.")
                        TextButton(onClick = { onOpenExisting(dup.id) }) { Text("Open ${dup.name}") }
                    }
                }
            }
            if (clientId == null) {
                OutlinedTextField(
                    altPhone, { altPhone = it }, label = { Text("Alternate phone") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                )
            }
            OutlinedTextField(
                email, { email = it }, label = { Text("Email") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            )
            DropdownField("Lead source *", LeadSource.entries, leadSource, { it.label }, { leadSource = it }, Modifier.fillMaxWidth())
            DropdownField("Status", ClientStatus.entries, status, { it.label }, { if (it != null) status = it }, Modifier.fillMaxWidth())
            OutlinedTextField(notes, { notes = it }, label = { Text("Notes") }, minLines = 3, modifier = Modifier.fillMaxWidth())
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            val valid = loaded && name.isNotBlank() && leadSource != null &&
                (clientId != null || PhoneNumbers.normalize(phone) != null) && duplicate == null
            Button(onClick = ::save, enabled = valid && !busy, modifier = Modifier.fillMaxWidth()) {
                Text(if (busy) "Saving…" else "Save")
            }
        }
    }
}

private fun ApiException.existingClient(): ClientRef? = runCatching {
    val c = (error?.details as JsonObject).getValue("existingClient").jsonObject
    ClientRef(
        id = c.getValue("id").jsonPrimitive.content,
        name = c.getValue("name").jsonPrimitive.content,
        primaryPhone = c.getValue("primaryPhone").jsonPrimitive.content,
    )
}.getOrNull()
