package com.brokerbuddy.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class CallMode(val label: String, val description: String) {
    DIRECT("Direct Call", "Calls ring your team. The AI never answers."),
    AI_RECEPTIONIST("AI Receptionist", "The AI answers every call and notes the caller's requirements."),
    SMART_ASSISTANT("Smart Call Assistant", "Your team's phone rings first; the AI answers if nobody picks up or it's after hours."),
}

@Serializable
enum class AssistantVoice(val label: String, val description: String) {
    RECORDED_STANDARD("Recorded greeting + standard AI voice", "Your recording plays first, then the phone service's standard voice continues."),
    RECORDED_NATURAL("Recorded greeting + natural AI voice", "A more natural-sounding voice after your greeting."),
    CUSTOM_AI_VOICE("Custom AI voice", "A voice created from yours by a provider that supports it, only with your verified consent."),
}

@Serializable
enum class GreetingLanguage(val label: String) {
    HINGLISH("Hinglish"),
    HINDI("हिन्दी · Hindi"),
    ENGLISH("English"),
    MARATHI("मराठी · Marathi"),
}

@Serializable
enum class UnclearBehavior(val label: String) {
    TAKE_CALLBACK("Promise a callback and end the call"),
    TRANSFER("Hand the call to a person"),
}

@Serializable
enum class HumanTransfer(val label: String) {
    ON_REQUEST("When the caller asks for a person"),
    NEVER("Never; take a callback instead"),
}

/** ISO weekdays (1 = Monday), "HH:mm" in [timeZone]. */
@Serializable
data class BusinessHours(
    val days: List<Int> = listOf(1, 2, 3, 4, 5, 6),
    val start: String = "09:30",
    val end: String = "20:00",
    val timeZone: String = "Asia/Kolkata",
)

@Serializable
data class CallAssistantSettings(
    val enabled: Boolean = false,
    val mode: CallMode = CallMode.DIRECT,
    val voice: AssistantVoice = AssistantVoice.RECORDED_STANDARD,
    val customGreetingEnabled: Boolean = false,
    val defaultLanguage: GreetingLanguage = GreetingLanguage.HINGLISH,
    val businessHours: BusinessHours = BusinessHours(),
    val callbackReminder: Boolean = true,
    val callbackDelayMinutes: Int = 15,
    val callbackAssigneeId: String? = null,
    val unclearBehavior: UnclearBehavior = UnclearBehavior.TAKE_CALLBACK,
    val maxUnclearRetries: Int = 2,
    val humanTransfer: HumanTransfer = HumanTransfer.ON_REQUEST,
    val transferNumber: String? = null,
    val businessNumbers: List<String> = emptyList(),
)

@Serializable
data class Greeting(
    val language: GreetingLanguage,
    val script: String,
    val isDefaultScript: Boolean = true,
    val hasAudio: Boolean = false,
    val mimeType: String? = null,
    val size: Long? = null,
    val durationMs: Long? = null,
    val updatedAt: String? = null,
)

@Serializable
data class VoiceAvailability(val available: Boolean, val reason: String? = null)

@Serializable
data class CallAssistantCapabilities(
    /** The configured telephony provider, or null when calls can't reach the assistant yet. */
    val provider: String? = null,
    val voices: Map<AssistantVoice, VoiceAvailability> = emptyMap(),
    val greetingFormats: List<String> = emptyList(),
    val maxGreetingSeconds: Int = 60,
)

@Serializable
data class CallAssistantInfo(
    val settings: CallAssistantSettings,
    val greetings: List<Greeting>,
    val capabilities: CallAssistantCapabilities = CallAssistantCapabilities(),
)

@Serializable
data class CallAssistantSettingsResponse(val settings: CallAssistantSettings)

@Serializable
data class GreetingScriptRequest(val script: String)

@Serializable
data class GreetingResponse(val greeting: Greeting, val warnings: List<String> = emptyList())

@Serializable
data class TestCallRequest(val callerNumber: String? = null)

@Serializable
data class TestCallLine(val kind: String, val text: String? = null, val language: GreetingLanguage? = null)

@Serializable
data class TestCallResponse(val callId: String = "", val lines: List<TestCallLine> = emptyList(), val next: String = "listen")

@Serializable
data class TestTurnRequest(val text: String)

@Serializable
data class AiCall(
    val id: String,
    val fromNumber: String? = null,
    val status: String,
    val language: GreetingLanguage? = null,
    val callbackRequested: Boolean = false,
    val humanRequested: Boolean = false,
    val summary: String? = null,
    val clientId: String? = null,
    val startedAt: String,
    val durationSec: Int? = null,
)

@Serializable
data class AiCallList(val calls: List<AiCall>)
