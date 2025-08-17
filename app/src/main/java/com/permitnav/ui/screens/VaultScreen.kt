package com.permitnav.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import com.permitnav.data.models.TruckDimensions
import com.permitnav.data.models.VehicleInfo
import com.permitnav.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultScreen(
    onNavigateBack: () -> Unit,
    onSelectPermit: (String) -> Unit
) {
    var selectedFilter by remember { mutableStateOf(PermitFilter.ALL) }
    val permits = remember { getSamplePermits() }
    
    val filteredPermits = when (selectedFilter) {
        PermitFilter.ALL -> permits
        PermitFilter.ACTIVE -> permits.filter { it.expirationDate.after(Date()) }
        PermitFilter.EXPIRED -> permits.filter { it.expirationDate.before(Date()) }
        PermitFilter.VALID -> permits.filter { it.isValid }
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Permit Vault") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = PrimaryOrange,
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White
                ),
                actions = {
                    IconButton(onClick = { /* Sort */ }) {
                        Icon(
                            Icons.Default.Sort,
                            contentDescription = "Sort",
                            tint = Color.White
                        )
                    }
                    IconButton(onClick = { /* Search */ }) {
                        Icon(
                            Icons.Default.Search,
                            contentDescription = "Search",
                            tint = Color.White
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(BackgroundLight)
        ) {
            FilterChips(
                selectedFilter = selectedFilter,
                onFilterChange = { selectedFilter = it },
                allCount = permits.size,
                activeCount = permits.count { it.expirationDate.after(Date()) },
                expiredCount = permits.count { it.expirationDate.before(Date()) },
                validCount = permits.count { it.isValid }
            )
            
            if (filteredPermits.isEmpty()) {
                EmptyVaultState(selectedFilter)
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(filteredPermits) { permit ->
                        PermitVaultCard(
                            permit = permit,
                            onClick = { onSelectPermit(permit.id) }
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilterChips(
    selectedFilter: PermitFilter,
    onFilterChange: (PermitFilter) -> Unit,
    allCount: Int,
    activeCount: Int,
    expiredCount: Int,
    validCount: Int
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        FilterChip(
            selected = selectedFilter == PermitFilter.ALL,
            onClick = { onFilterChange(PermitFilter.ALL) },
            label = { Text("All ($allCount)") }
        )
        FilterChip(
            selected = selectedFilter == PermitFilter.ACTIVE,
            onClick = { onFilterChange(PermitFilter.ACTIVE) },
            label = { Text("Active ($activeCount)") }
        )
        FilterChip(
            selected = selectedFilter == PermitFilter.EXPIRED,
            onClick = { onFilterChange(PermitFilter.EXPIRED) },
            label = { Text("Expired ($expiredCount)") }
        )
        FilterChip(
            selected = selectedFilter == PermitFilter.VALID,
            onClick = { onFilterChange(PermitFilter.VALID) },
            label = { Text("Valid ($validCount)") }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermitVaultCard(
    permit: Permit,
    onClick: () -> Unit
) {
    val dateFormat = SimpleDateFormat("MMM dd, yyyy", Locale.US)
    val isExpired = permit.expirationDate.before(Date())
    
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(
            containerColor = when {
                isExpired -> Color.White
                permit.isValid -> Color.White
                else -> Color.White
            }
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        permit.permitNumber,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        permit.state,
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                }
                
                StatusChip(
                    isValid = permit.isValid,
                    isExpired = isExpired
                )
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                InfoItem(
                    icon = Icons.Default.CalendarToday,
                    label = "Issued",
                    value = dateFormat.format(permit.issueDate)
                )
                InfoItem(
                    icon = Icons.Default.Event,
                    label = "Expires",
                    value = dateFormat.format(permit.expirationDate),
                    isWarning = isExpired
                )
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                permit.dimensions.weight?.let { weight ->
                    InfoItem(
                        icon = Icons.Default.Scale,
                        label = "Weight",
                        value = "${weight.toInt()} lbs"
                    )
                }
                permit.dimensions.length?.let { length ->
                    InfoItem(
                        icon = Icons.Default.Straighten,
                        label = "Length",
                        value = "$length ft"
                    )
                }
            }
            
            if (permit.restrictions.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = null,
                        tint = WarningYellow,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        "${permit.restrictions.size} restrictions apply",
                        style = MaterialTheme.typography.bodySmall,
                        color = WarningYellow
                    )
                }
            }
        }
    }
}

@Composable
fun StatusChip(
    isValid: Boolean,
    isExpired: Boolean
) {
    val (text, color, icon) = when {
        isExpired -> Triple("Expired", ErrorRed, Icons.Default.Cancel)
        isValid -> Triple("Valid", SuccessGreen, Icons.Default.CheckCircle)
        else -> Triple("Invalid", WarningYellow, Icons.Default.Warning)
    }
    
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = color.copy(alpha = 0.1f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text,
                style = MaterialTheme.typography.labelSmall,
                color = color,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
fun InfoItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
    isWarning: Boolean = false
) {
    Row(
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (isWarning) ErrorRed else TextSecondary,
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Column {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary
            )
            Text(
                value,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = if (isWarning) ErrorRed else TextPrimary
            )
        }
    }
}

@Composable
fun EmptyVaultState(filter: PermitFilter) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                Icons.Default.FolderOpen,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = Color.Gray.copy(alpha = 0.5f)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                when (filter) {
                    PermitFilter.ALL -> "No permits stored"
                    PermitFilter.ACTIVE -> "No active permits"
                    PermitFilter.EXPIRED -> "No expired permits"
                    PermitFilter.VALID -> "No valid permits"
                },
                style = MaterialTheme.typography.titleMedium,
                color = TextSecondary
            )
        }
    }
}

enum class PermitFilter {
    ALL, ACTIVE, EXPIRED, VALID
}

fun getSamplePermits(): List<Permit> {
    val calendar = Calendar.getInstance()
    return listOf(
        Permit(
            id = "1",
            state = "IN",
            permitNumber = "IN-2024-001234",
            issueDate = Date(),
            expirationDate = calendar.apply { add(Calendar.DAY_OF_YEAR, 3) }.time,
            vehicleInfo = VehicleInfo(null, null, null, "ABC123", null, null),
            dimensions = TruckDimensions(95.0, 12.0, 14.5, 85000.0, 5, null, null),
            routeDescription = "I-65 North to US-31",
            restrictions = listOf("Daylight hours only", "No Sunday travel"),
            rawImagePath = null,
            ocrText = null,
            isValid = true
        )
    )
}