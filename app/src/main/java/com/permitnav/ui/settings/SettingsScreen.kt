package com.permitnav.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit = {}
) {
    val auth = remember { FirebaseAuth.getInstance() }
    val db = remember { FirebaseFirestore.getInstance() }
    val functions = remember { FirebaseFunctions.getInstance() }
    val scope = rememberCoroutineScope()
    
    var currentRole by remember { mutableStateOf("driver") }
    var isLoading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var messageType by remember { mutableStateOf<MessageType>(MessageType.INFO) }

    // Load current role from token
    LaunchedEffect(Unit) {
        try {
            auth.currentUser?.getIdToken(true)?.addOnSuccessListener { tokenResult ->
                currentRole = (tokenResult.claims["role"] as? String) ?: "driver"
            }
        } catch (e: Exception) {
            message = "Error loading current role: ${e.message}"
            messageType = MessageType.ERROR
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = null,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Settings")
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.AccountCircle, contentDescription = "Back")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Current Role Section
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        "Current Role", 
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            if (currentRole == "driver") Icons.Default.LocalShipping else Icons.Default.AccountCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            currentRole.replaceFirstChar { it.uppercase() },
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            // Role Switching Section
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        "Switch Role (Testing)",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Switch between driver and dispatcher views for testing purposes.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(16.dp))

                    Column(
                        modifier = Modifier.selectableGroup()
                    ) {
                        RoleRadioButton(
                            selected = currentRole == "driver",
                            role = "driver",
                            title = "Driver",
                            description = "Access permit scanning, route navigation, and driver tools",
                            icon = Icons.Default.LocalShipping,
                            enabled = !isLoading
                        ) {
                            if (currentRole != "driver") {
                                scope.launch {
                                    setRole("driver", auth, functions, 
                                          onLoading = { isLoading = it },
                                          onResult = { success, msg, type ->
                                              if (success) currentRole = "driver"
                                              message = msg
                                              messageType = type
                                          })
                                }
                            }
                        }

                        Spacer(Modifier.height(12.dp))

                        RoleRadioButton(
                            selected = currentRole == "dispatcher",
                            role = "dispatcher", 
                            title = "Dispatcher",
                            description = "Manage loads, assign routes, and oversee fleet operations",
                            icon = Icons.Default.AccountCircle,
                            enabled = !isLoading
                        ) {
                            if (currentRole != "dispatcher") {
                                scope.launch {
                                    setRole("dispatcher", auth, functions,
                                          onLoading = { isLoading = it },
                                          onResult = { success, msg, type ->
                                              if (success) currentRole = "dispatcher"
                                              message = msg
                                              messageType = type
                                          })
                                }
                            }
                        }
                    }
                }
            }

            // Status Message
            message?.let { msg ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = when (messageType) {
                            MessageType.SUCCESS -> MaterialTheme.colorScheme.primaryContainer
                            MessageType.ERROR -> MaterialTheme.colorScheme.errorContainer
                            MessageType.INFO -> MaterialTheme.colorScheme.surfaceVariant
                        }
                    )
                ) {
                    Text(
                        msg,
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = when (messageType) {
                            MessageType.SUCCESS -> MaterialTheme.colorScheme.onPrimaryContainer
                            MessageType.ERROR -> MaterialTheme.colorScheme.onErrorContainer
                            MessageType.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }

            if (isLoading) {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }
        }
    }
}

@Composable
private fun RoleRadioButton(
    selected: Boolean,
    role: String,
    title: String,
    description: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth(),
        onClick = if (enabled) onClick else { {} },
        enabled = enabled,
        colors = CardDefaults.cardColors(
            containerColor = if (selected) 
                MaterialTheme.colorScheme.primaryContainer 
            else 
                MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (selected) 
                    MaterialTheme.colorScheme.onPrimaryContainer 
                else 
                    MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.width(12.dp))
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    color = if (selected) 
                        MaterialTheme.colorScheme.onPrimaryContainer 
                    else 
                        MaterialTheme.colorScheme.onSurface
                )
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (selected) 
                        MaterialTheme.colorScheme.onPrimaryContainer 
                    else 
                        MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            RadioButton(
                selected = selected,
                onClick = if (enabled) onClick else null,
                enabled = enabled
            )
        }
    }
}

private enum class MessageType {
    SUCCESS, ERROR, INFO
}

private suspend fun setRole(
    role: String,
    auth: FirebaseAuth,
    functions: FirebaseFunctions,
    onLoading: (Boolean) -> Unit,
    onResult: (Boolean, String, MessageType) -> Unit
) {
    try {
        onLoading(true)
        
        val uid = auth.currentUser?.uid
        if (uid == null) {
            onResult(false, "Not signed in", MessageType.ERROR)
            return
        }

        // Call the Cloud Function to set role with custom claims
        val data = hashMapOf(
            "uid" to uid,
            "role" to role
        )

        val result = functions
            .getHttpsCallable("setUserRole")
            .call(data)
            .await()

        // Force token refresh to get new claims
        auth.currentUser?.getIdToken(true)?.await()

        onResult(true, "Role set to $role successfully!", MessageType.SUCCESS)
    } catch (e: Exception) {
        android.util.Log.e("SettingsScreen", "Failed to set role", e)
        onResult(false, "Failed to set role: ${e.message}", MessageType.ERROR)
    } finally {
        onLoading(false)
    }
}