package com.permitnav.ui.screens

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.permitnav.ui.viewmodels.PermitReviewViewModel
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.permitnav.data.models.Permit
import com.permitnav.rules.ComplianceResult
import com.permitnav.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermitReviewScreen(
    permitId: String,
    imageUri: String?,
    onNavigateBack: () -> Unit,
    onNavigateToNavigation: () -> Unit
) {
    val context = LocalContext.current
    val viewModel = remember { PermitReviewViewModel(context.applicationContext as android.app.Application) }
    val uiState by viewModel.uiState.collectAsState()
    
    // Process the image when screen loads
    LaunchedEffect(imageUri) {
        android.util.Log.d("PermitReviewScreen", "LaunchedEffect triggered with imageUri: $imageUri, permitId: $permitId")
        imageUri?.let { uriString ->
            try {
                android.util.Log.d("PermitReviewScreen", "Parsing URI: $uriString")
                val uri = Uri.parse(uriString)
                android.util.Log.d("PermitReviewScreen", "Starting image processing for URI: $uri")
                viewModel.processPermitImage(uri, permitId)
            } catch (e: Exception) {
                android.util.Log.e("PermitReviewScreen", "Failed to parse URI: $uriString", e)
            }
        }
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Review Permit") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
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
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shadowElevation = 8.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    OutlinedButton(
                        onClick = { /* Validation is automatic */ },
                        modifier = Modifier.weight(1f),
                        enabled = uiState.permit != null
                    ) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Validate")
                    }
                    Spacer(Modifier.width(16.dp))
                    Button(
                        onClick = onNavigateToNavigation,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = PrimaryOrange
                        )
                    ) {
                        Icon(Icons.Default.Navigation, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Plan Route")
                    }
                }
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .background(BackgroundLight)
        ) {
            // Show enhanced permit details first when available
            uiState.permit?.let { permit ->
                EnhancedPermitDetailsCard(permit)
            }
            
            // Show compliance results
            uiState.complianceResult?.let { result ->
                ValidationResultsCard(result, uiState.permit)
            }
            
            // Show processing state or collapsible OCR text at bottom
            if (uiState.isProcessing) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                            .height(200.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            CircularProgressIndicator(color = PrimaryOrange)
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                "Processing permit...",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextSecondary
                            )
                        }
                    }
                }
            } else {
                // Always show OCR section when not processing, even if text is blank
                CollapsibleOCRCard(
                    extractedText = uiState.extractedText,
                    onTextChange = { viewModel.updateExtractedText(it) }
                )
            }
            
            uiState.error?.let { error ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Text(
                            "Error",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Text(
                            error,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun EnhancedPermitDetailsCard(permit: Permit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Column(
            modifier = Modifier.padding(20.dp)
        ) {
            // Header with permit number
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "📋 Permit Details",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = SecondaryBlue
                )
                Surface(
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                    color = if (permit.isValid) SuccessGreen.copy(alpha = 0.1f) else ErrorRed.copy(alpha = 0.1f),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp, 
                        if (permit.isValid) SuccessGreen else ErrorRed
                    )
                ) {
                    Text(
                        if (permit.isValid) "Valid" else "Issues",
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium,
                        color = if (permit.isValid) SuccessGreen else ErrorRed
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(20.dp))
            
            // Basic permit info
            InfoSection(
                title = "Permit Information",
                icon = "📄"
            ) {
                EnhancedFieldRow("Permit Number", permit.permitNumber, isHighlight = true)
                EnhancedFieldRow("State", permit.state.uppercase())
                EnhancedFieldRow(
                    "Expires", 
                    java.text.SimpleDateFormat("MMM dd, yyyy", java.util.Locale.US).format(permit.expirationDate)
                )
            }
            
            // Route information (if available)
            if (!permit.origin.isNullOrBlank() || !permit.destination.isNullOrBlank() || !permit.routeDescription.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(16.dp))
                InfoSection(
                    title = "Route Information",
                    icon = "📍"
                ) {
                    permit.origin?.let { origin ->
                        EnhancedFieldRow("From", origin, isHighlight = true)
                    }
                    permit.destination?.let { destination ->
                        EnhancedFieldRow("To", destination, isHighlight = true)
                    }
                    permit.routeDescription?.let { route ->
                        EnhancedFieldRow("Route", route)
                    }
                }
            }
            
            // Dimensions
            Spacer(modifier = Modifier.height(16.dp))
            InfoSection(
                title = "Dimensions",
                icon = "📏"
            ) {
                permit.dimensions.weight?.let { weight ->
                    EnhancedFieldRow("Weight", "${java.text.NumberFormat.getInstance().format(weight.toInt())} lbs", isHighlight = true)
                }
                
                // Size as single line if we have length, width, height
                val dimensions = listOfNotNull(
                    permit.dimensions.length?.let { "${it}'"},
                    permit.dimensions.width?.let { "${it}'"},
                    permit.dimensions.height?.let { "${it}'"}
                )
                if (dimensions.isNotEmpty()) {
                    EnhancedFieldRow("Size (L×W×H)", dimensions.joinToString(" × "))
                }
                
                permit.dimensions.axles?.let { axles ->
                    EnhancedFieldRow("Axles", axles.toString())
                }
            }
            
            // Restrictions (if any)
            if (permit.restrictions.isNotEmpty()) {
                Spacer(modifier = Modifier.height(16.dp))
                InfoSection(
                    title = "Restrictions",
                    icon = "⚠️"
                ) {
                    permit.restrictions.forEach { restriction ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp)
                        ) {
                            Text(
                                "• ",
                                style = MaterialTheme.typography.bodyMedium,
                                color = WarningYellow
                            )
                            Text(
                                restriction,
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextPrimary
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun InfoSection(
    title: String,
    icon: String,
    content: @Composable () -> Unit
) {
    Column {
        Text(
            "$icon $title",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = SecondaryBlue,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        content()
    }
}

@Composable
fun EnhancedFieldRow(
    label: String, 
    value: String, 
    isHighlight: Boolean = false
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
            modifier = Modifier.weight(1f)
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (isHighlight) FontWeight.Bold else FontWeight.Medium,
            color = if (isHighlight) SecondaryBlue else TextPrimary,
            modifier = Modifier.weight(1.5f)
        )
    }
}

@Composable
fun CollapsibleOCRCard(
    extractedText: String,
    onTextChange: (String) -> Unit
) {
    var isExpanded by remember { mutableStateOf(true) }
    
    // Debug logging to see what text we're getting
    android.util.Log.d("CollapsibleOCRCard", "Received text length: ${extractedText.length}")
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceLight)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { isExpanded = !isExpanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "📄 Raw OCR Text ${if (extractedText.isNotBlank()) "(${extractedText.length} chars)" else "(Empty)"}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = SecondaryBlue
                )
                Icon(
                    if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (isExpanded) "Collapse" else "Expand",
                    tint = SecondaryBlue
                )
            }
            
            if (isExpanded) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    "Edit the text below if OCR missed anything:",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextPrimary,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                OutlinedTextField(
                    value = extractedText,
                    onValueChange = onTextChange,
                    label = { Text("Extracted Text") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 150.dp, max = 300.dp),
                    maxLines = 12,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = PrimaryOrange,
                        cursorColor = PrimaryOrange,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        focusedLabelColor = PrimaryOrange,
                        unfocusedLabelColor = TextSecondary
                    )
                )
            } else {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "Tap to view/edit the raw OCR text",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )
            }
        }
    }
}

@Composable
fun PermitFieldsCard(permit: Permit) {
    // Legacy function - keeping for compatibility
    EnhancedPermitDetailsCard(permit)
}

@Composable
fun FieldRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
fun ValidationResultsCard(complianceResult: ComplianceResult, permit: Permit?) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (complianceResult.isCompliant) 
                SuccessGreen.copy(alpha = 0.05f) 
            else 
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    if (complianceResult.isCompliant) Icons.Default.CheckCircle else Icons.Default.Warning,
                    contentDescription = null,
                    tint = if (complianceResult.isCompliant) SuccessGreen else MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (complianceResult.isCompliant) "Permit Valid" else "Permit Issues Found",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (complianceResult.isCompliant) SuccessGreen else MaterialTheme.colorScheme.error
                )
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            if (complianceResult.isCompliant) {
                Text(
                    "✓ All dimensions within limits",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextPrimary
                )
                Text(
                    "✓ Valid permit dates",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextPrimary
                )
                Text(
                    "✓ Route restrictions identified",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextPrimary
                )
            } else {
                complianceResult.violations.forEach { violation ->
                    Text(
                        "⚠ $violation",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}