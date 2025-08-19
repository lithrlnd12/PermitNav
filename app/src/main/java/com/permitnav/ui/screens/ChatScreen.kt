package com.permitnav.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.permitnav.data.models.*
import com.permitnav.ui.theme.*
import com.permitnav.ui.viewmodels.ChatViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    permitId: String? = null,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val viewModel: ChatViewModel = viewModel()
    val uiState by viewModel.uiState.collectAsState()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    
    var messageText by remember { mutableStateOf("") }
    
    // Load permit context if provided
    LaunchedEffect(permitId) {
        permitId?.let { viewModel.loadPermitContext(it) }
    }
    
    // Auto-scroll to bottom when new messages arrive
    LaunchedEffect(uiState.messages.size) {
        if (uiState.messages.isNotEmpty()) {
            scope.launch {
                listState.animateScrollToItem(uiState.messages.size - 1)
            }
        }
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Column {
                        Text(
                            "AI Cargo Assistant",
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (uiState.selectedPermit != null) 
                                "Permit: ${uiState.selectedPermit!!.permitNumber} (${uiState.selectedPermit!!.state.uppercase()})"
                            else 
                                "General Questions",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.8f)
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.Default.ArrowBack, 
                            contentDescription = "Back",
                            tint = Color.White
                        )
                    }
                },
                actions = {
                    // Clear chat
                    IconButton(onClick = { viewModel.clearChat() }) {
                        Icon(
                            Icons.Default.Clear, 
                            contentDescription = "Clear Chat",
                            tint = Color.White
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = PrimaryOrange,
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White
                )
            )
        },
        bottomBar = {
            ChatInputBar(
                message = messageText,
                onMessageChange = { messageText = it },
                onSendMessage = {
                    if (messageText.isNotBlank()) {
                        viewModel.sendMessage(messageText)
                        messageText = ""
                    }
                },
                isLoading = uiState.isLoading,
                availablePermits = uiState.availablePermits,
                selectedPermit = uiState.selectedPermit,
                onPermitSelected = { permit -> viewModel.selectPermit(permit) },
                modifier = Modifier.fillMaxWidth()
            )
        }
    ) { paddingValues ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(SecondaryBlue)
        ) {
            if (uiState.messages.isEmpty()) {
                ChatWelcomeScreen(
                    hasPermitContext = uiState.hasPermitContext,
                    onQuickQuestion = { question ->
                        messageText = question
                    }
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(uiState.messages) { message ->
                        ChatMessageItem(
                            message = message,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    
                    if (uiState.isLoading) {
                        item {
                            ChatTypingIndicator()
                        }
                    }
                }
            }
            
            // Error display
            uiState.error?.let { error ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = error,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ChatWelcomeScreen(
    hasPermitContext: Boolean,
    onQuickQuestion: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Default.SmartToy,
            contentDescription = null,
            modifier = Modifier.size(80.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Text(
            text = "AI Dispatch Assistant",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        
        Text(
            text = if (hasPermitContext) 
                "Ask questions about your current permit and state regulations"
            else 
                "Ask questions about state regulations, escort requirements, and permit compliance",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        
        Spacer(modifier = Modifier.height(32.dp))
        
        Text(
            text = "Quick Questions:",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        val quickQuestions = if (hasPermitContext) {
            listOf(
                "Do I need escorts for this permit?",
                "What are the travel restrictions?",
                "Is this permit compliant?",
                "What time restrictions apply?"
            )
        } else {
            listOf(
                "What are the escort requirements?",
                "What are the weight limits?",
                "What are the operating hours?",
                "How do I contact the DOT office?"
            )
        }
        
        quickQuestions.forEach { question ->
            OutlinedButton(
                onClick = { onQuickQuestion(question) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
            ) {
                Text(text = question)
            }
        }
    }
}

@Composable
fun ChatMessageItem(
    message: ChatMessage,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = if (message.isFromUser) Arrangement.End else Arrangement.Start
    ) {
        if (!message.isFromUser) {
            // AI avatar
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.SmartToy,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onPrimary
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
        }
        
        Card(
            modifier = Modifier.widthIn(max = 280.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (message.isFromUser) 
                    MaterialTheme.colorScheme.primary 
                else 
                    MaterialTheme.colorScheme.surfaceVariant
            ),
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (message.isFromUser) 16.dp else 4.dp,
                bottomEnd = if (message.isFromUser) 4.dp else 16.dp
            )
        ) {
            Column(
                modifier = Modifier.padding(12.dp)
            ) {
                Text(
                    text = message.content,
                    color = if (message.isFromUser) 
                        MaterialTheme.colorScheme.onPrimary 
                    else 
                        MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium
                )
                
                // Show compliance results if it's a compliance message
                if (message.messageType == MessageType.COMPLIANCE_RESULT && !message.isFromUser) {
                    // TODO: Parse and display structured compliance data
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Tap for details",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (message.isFromUser) 
                            MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f)
                        else 
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            }
        }
        
        if (message.isFromUser) {
            Spacer(modifier = Modifier.width(8.dp))
            // User avatar
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.secondary),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Person,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSecondary
                )
            }
        }
    }
}

@Composable
fun ChatInputBar(
    message: String,
    onMessageChange: (String) -> Unit,
    onSendMessage: () -> Unit,
    isLoading: Boolean,
    availablePermits: List<Permit>,
    selectedPermit: Permit?,
    onPermitSelected: (Permit?) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shadowElevation = 8.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            // Permit selector plus button
            Box {
                var expanded by remember { mutableStateOf(false) }
                
                IconButton(
                    onClick = { expanded = true },
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        if (selectedPermit != null) Icons.Default.Description else Icons.Default.Add,
                        contentDescription = "Select Permit",
                        tint = if (selectedPermit != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    // Clear selection option
                    DropdownMenuItem(
                        text = { 
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.Clear, 
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("General Questions")
                            }
                        },
                        onClick = {
                            onPermitSelected(null)
                            expanded = false
                        }
                    )
                    
                    if (availablePermits.isNotEmpty()) {
                        HorizontalDivider()
                        
                        availablePermits.forEach { permit ->
                            DropdownMenuItem(
                                text = { 
                                    Column {
                                        Text(
                                            text = permit.permitNumber,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = "${permit.state.uppercase()} • ${permit.dimensions.width?.let { "${it}' W" } ?: ""} ${permit.dimensions.height?.let { "${it}' H" } ?: ""}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                },
                                onClick = {
                                    onPermitSelected(permit)
                                    expanded = false
                                }
                            )
                        }
                    } else {
                        DropdownMenuItem(
                            text = { 
                                Text(
                                    "No permits available",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            },
                            onClick = { }
                        )
                    }
                }
            }
            
            Spacer(modifier = Modifier.width(8.dp))
            
            OutlinedTextField(
                value = message,
                onValueChange = onMessageChange,
                placeholder = { 
                    Text(
                        if (selectedPermit != null) 
                            "Ask about ${selectedPermit.permitNumber} (${selectedPermit.state.uppercase()})..."
                        else 
                            "Ask about regulations, escorts, restrictions..."
                    )
                },
                modifier = Modifier.weight(1f),
                maxLines = 4,
                enabled = !isLoading
            )
            
            Spacer(modifier = Modifier.width(8.dp))
            
            FloatingActionButton(
                onClick = onSendMessage,
                modifier = Modifier.size(48.dp),
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(
                        Icons.Default.Send,
                        contentDescription = "Send message"
                    )
                }
            }
        }
    }
}

@Composable
fun ChatTypingIndicator(
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.Start
    ) {
        // AI avatar
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.SmartToy,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onPrimary
            )
        }
        
        Spacer(modifier = Modifier.width(8.dp))
        
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            ),
            shape = RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp)
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "AI is thinking",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.width(8.dp))
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp
                )
            }
        }
    }
}