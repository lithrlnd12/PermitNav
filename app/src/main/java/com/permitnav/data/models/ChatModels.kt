package com.permitnav.data.models

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.Date

@Entity(tableName = "chat_messages")
data class ChatMessage(
    @PrimaryKey val id: String,
    val conversationId: String,
    val content: String,
    val isFromUser: Boolean,
    val timestamp: Date = Date(),
    val stateContext: String? = null,
    val permitContext: String? = null,
    val messageType: MessageType = MessageType.TEXT
)

enum class MessageType {
    TEXT,
    COMPLIANCE_RESULT,
    ERROR,
    SYSTEM
}

@Entity(tableName = "chat_conversations")
data class ChatConversation(
    @PrimaryKey val id: String,
    val title: String,
    val stateCode: String? = null,
    val lastMessage: String? = null,
    val lastMessageTime: Date = Date(),
    val createdAt: Date = Date()
)

data class ComplianceResponse(
    val is_compliant: Boolean,
    val violations: List<String>,
    val escorts: String,
    val travel_restrictions: String,
    val notes: String,
    val contact_info: ContactInfo? = null
)

data class ContactInfo(
    val department: String,
    val phone: String,
    val email: String? = null,
    val website: String? = null,
    val office_hours: String? = null
)

data class ChatRequest(
    val stateKey: String,
    val permitText: String,
    val userQuestion: String,
    val conversationId: String? = null
)