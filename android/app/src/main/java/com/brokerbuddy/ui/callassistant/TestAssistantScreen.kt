package com.brokerbuddy.ui.callassistant

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.brokerbuddy.core.model.TestCallRequest
import com.brokerbuddy.core.model.TestCallResponse
import com.brokerbuddy.core.model.TestTurnRequest
import com.brokerbuddy.ui.common.BackTopBar
import com.brokerbuddy.ui.common.appContainer
import com.brokerbuddy.ui.common.toast
import com.brokerbuddy.ui.theme.Brand
import com.brokerbuddy.ui.theme.brand
import kotlinx.coroutines.launch

private data class Bubble(val fromCaller: Boolean, val text: String)

/**
 * "Test your assistant": a typed conversation with the same engine that answers real
 * calls. Nothing is saved to the CRM.
 */
@Composable
fun TestAssistantScreen(onBack: () -> Unit) {
    val container = appContainer()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val bubbles = remember { mutableStateListOf<Bubble>() }
    var callId by remember { mutableStateOf<String?>(null) }
    var ended by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val list = rememberLazyListState()

    fun show(r: TestCallResponse) {
        r.lines.forEach { line ->
            bubbles += Bubble(false, if (line.kind == "recording") "▶ Your recorded greeting plays here" else line.text.orEmpty())
        }
        if (r.next != "listen") {
            ended = true
            bubbles += Bubble(false, if (r.next == "transfer") "— The call is handed to your team —" else "— Call ended —")
        }
    }

    fun start() {
        scope.launch {
            busy = true
            bubbles.clear(); ended = false
            container.api.call { startTestCall(TestCallRequest(callerNumber = "+919800000000")) }
                .onSuccess { callId = it.callId; show(it) }
                .onFailure { toast(context, it.message ?: "Couldn't start the test") }
            busy = false
        }
    }

    fun send() {
        val id = callId ?: return
        val text = input.trim()
        input = ""
        bubbles += Bubble(true, text.ifEmpty { "(silence)" })
        scope.launch {
            busy = true
            container.api.call { testCallTurn(id, TestTurnRequest(text)) }
                .onSuccess { show(it) }
                .onFailure { toast(context, it.message ?: "Something went wrong") }
            busy = false
        }
    }

    LaunchedEffect(Unit) { start() }
    LaunchedEffect(bubbles.size) { if (bubbles.isNotEmpty()) list.animateScrollToItem(bubbles.size - 1) }

    Scaffold(topBar = { BackTopBar("Test your assistant", onBack) { TextButton(onClick = { start() }, enabled = !busy) { Text("Restart") } } }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().imePadding()) {
            Text(
                "You are the caller. Type what a client might say — in Hinglish, Hindi, Marathi or English. Nothing is saved.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.brand.muted, modifier = Modifier.padding(16.dp),
            )
            LazyColumn(state = list, modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(bubbles) { b ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (b.fromCaller) Arrangement.End else Arrangement.Start) {
                        Box(
                            Modifier.widthIn(max = 300.dp)
                                .background(if (b.fromCaller) Brand.Blue else MaterialTheme.brand.card, RoundedCornerShape(16.dp))
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                        ) {
                            Text(b.text, color = if (b.fromCaller) Color.White else MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            }
            if (ended) {
                Button(onClick = { start() }, modifier = Modifier.fillMaxWidth().padding(12.dp)) { Text("Start another test call") }
            } else {
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = input, onValueChange = { input = it.take(500) }, enabled = callId != null && !busy,
                        placeholder = { Text("e.g. Mujhe Powai mein 2 BHK rent pe chahiye") }, modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { send() }, enabled = callId != null && !busy) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                    }
                }
            }
        }
    }
}
