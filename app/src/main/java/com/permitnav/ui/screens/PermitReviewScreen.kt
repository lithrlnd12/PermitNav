package com.permitnav.ui.screens

import android.net.Uri
import androidx.compose.foundation.background
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
    val viewModel = remember { PermitReviewViewModel(context) }
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
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        "OCR Results",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    if (uiState.isProcessing) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(200.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(color = PrimaryOrange)
                        }
                    } else {
                        OutlinedTextField(
                            value = uiState.extractedText,
                            onValueChange = { viewModel.updateExtractedText(it) },
                            label = { Text("Extracted Text") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 200.dp),
                            maxLines = 10
                        )
                    }
                }
            }
            
            uiState.permit?.let { permit ->
                PermitFieldsCard(permit)
            }
            
            uiState.complianceResult?.let { result ->
                ValidationResultsCard(result, uiState.permit)
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
fun PermitFieldsCard(permit: Permit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                "Permit Details",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(16.dp))
            
            FieldRow("Permit Number", permit.permitNumber)
            FieldRow("Issue Date", java.text.SimpleDateFormat("MM/dd/yyyy", java.util.Locale.US).format(permit.issueDate))
            FieldRow("Expiration", java.text.SimpleDateFormat("MM/dd/yyyy", java.util.Locale.US).format(permit.expirationDate))
            permit.dimensions.weight?.let { weight ->
                FieldRow("Weight", "${weight.toInt()} lbs")
            }
            permit.dimensions.height?.let { height ->
                FieldRow("Height", "$height ft")
            }
            permit.dimensions.width?.let { width ->
                FieldRow("Width", "$width ft")
            }
            permit.dimensions.length?.let { length ->
                FieldRow("Length", "$length ft")
            }
        }
    }
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
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    "✓ Valid permit dates",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    "✓ Route restrictions identified",
                    style = MaterialTheme.typography.bodyMedium
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