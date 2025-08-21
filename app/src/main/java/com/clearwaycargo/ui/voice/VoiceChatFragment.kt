package com.clearwaycargo.ui.voice

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.clearwaycargo.service.VoiceSessionService
import com.clearwaycargo.ui.voice.components.*
import com.clearwaycargo.ui.voice.VoiceState
import com.permitnav.ui.theme.ClearwayCargoTheme
import com.permitnav.data.models.Permit
import kotlinx.coroutines.launch

/**
 * Full-screen voice chat fragment with WebRTC integration
 * Provides natural voice interaction with AI dispatcher
 */
class VoiceChatFragment : Fragment() {
    
    companion object {
        private const val TAG = "VoiceChatFragment"
        private const val ARG_PERMIT_ID = "permit_id"
        
        fun newInstance(permitId: String? = null, onNavigateBack: (() -> Unit)? = null): VoiceChatFragment {
            return VoiceChatFragment().apply {
                arguments = Bundle().apply {
                    permitId?.let { putString(ARG_PERMIT_ID, it) }
                }
                // Store the navigation callback
                this.onNavigateBack = onNavigateBack
            }
        }
    }
    
    // Navigation callback
    private var onNavigateBack: (() -> Unit)? = null
    
    // Service binding
    private var voiceService: VoiceSessionService? = null
    private var isBound = false
    
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.d(TAG, "🔗 Voice service connected")
            val binder = service as VoiceSessionService.VoiceSessionBinder
            voiceService = binder.getService()
            isBound = true
            
            // Start voice session
            startVoiceSession()
        }
        
        override fun onServiceDisconnected(name: ComponentName?) {
            Log.d(TAG, "🔌 Voice service disconnected")
            voiceService = null
            isBound = false
        }
    }
    
    // Permission handling
    private val permissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val audioGranted = permissions[Manifest.permission.RECORD_AUDIO] ?: false
        val notificationGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions[Manifest.permission.POST_NOTIFICATIONS] ?: true // Not required below API 33
        } else {
            true
        }
        
        if (audioGranted && notificationGranted) {
            Log.d(TAG, "🎤 All required permissions granted")
            bindVoiceService()
        } else {
            Log.w(TAG, "❌ Required permissions denied - Audio: $audioGranted, Notification: $notificationGranted")
            showPermissionError()
        }
    }
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setContent {
                ClearwayCargoTheme {
                    VoiceChatScreen()
                }
            }
        }
    }
    
    override fun onResume() {
        super.onResume()
        Log.d(TAG, "▶️ Fragment resumed")
        
        // Request permission and start service
        requestAudioPermissionAndStartService()
    }
    
    override fun onPause() {
        super.onPause()
        Log.d(TAG, "⏸️ Fragment paused")
        
        // Stop session if finishing
        if (isRemoving || requireActivity().isFinishing) {
            stopVoiceSession()
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "🛑 Fragment destroyed")
        
        stopVoiceSession()
        unbindVoiceService()
    }
    
    /**
     * Request all required permissions and start voice service
     */
    private fun requestAudioPermissionAndStartService() {
        // Check all required permissions
        val audioGranted = ContextCompat.checkSelfPermission(
            requireContext(), Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        
        val notificationGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                requireContext(), Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true // Not required below API 33
        }
        
        when {
            audioGranted && notificationGranted -> {
                // All permissions already granted
                bindVoiceService()
            }
            shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) -> {
                // Show rationale and request permissions
                showPermissionRationale()
            }
            else -> {
                // Request permissions directly
                val permissionsToRequest = mutableListOf(Manifest.permission.RECORD_AUDIO)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !notificationGranted) {
                    permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
                }
                permissionsLauncher.launch(permissionsToRequest.toTypedArray())
            }
        }
    }
    
    /**
     * Bind to voice session service
     */
    private fun bindVoiceService() {
        Log.d(TAG, "🔗 Binding to voice service")
        
        val intent = Intent(requireContext(), VoiceSessionService::class.java)
        // Use startForegroundService for proper foreground service handling
        ContextCompat.startForegroundService(requireContext(), intent)
        requireContext().bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }
    
    /**
     * Unbind from voice session service
     */
    private fun unbindVoiceService() {
        if (isBound) {
            Log.d(TAG, "🔌 Unbinding from voice service")
            requireContext().unbindService(serviceConnection)
            isBound = false
        }
    }
    
    /**
     * Start voice session with permit context
     */
    private fun startVoiceSession() {
        val permitId = arguments?.getString(ARG_PERMIT_ID)
        
        lifecycleScope.launch {
            try {
                Log.d(TAG, "🎤 Starting voice session with permit: $permitId")
                
                // Load permit context if provided
                val permit = permitId?.let { id ->
                    // In production, load from repository
                    // permitRepository.getPermitById(id)
                    null // Placeholder
                }
                
                voiceService?.startSession(
                    initialGreeting = true,
                    permitContext = permit
                )
                
            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to start voice session", e)
                showSessionError(e.message)
            }
        }
    }
    
    /**
     * Stop voice session
     */
    private fun stopVoiceSession() {
        Log.d(TAG, "⏹️ Stopping voice session")
        voiceService?.stopSession()
    }
    
    /**
     * Show permission rationale dialog
     */
    private fun showPermissionRationale() {
        // In production, show a proper dialog explaining why microphone and notification access is needed
        val permissionsToRequest = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissionsLauncher.launch(permissionsToRequest.toTypedArray())
    }
    
    /**
     * Show permission error and close fragment
     */
    private fun showPermissionError() {
        Log.e(TAG, "❌ Cannot start voice chat without microphone permission")
        onNavigateBack?.invoke() ?: parentFragmentManager.popBackStack()
    }
    
    /**
     * Show session error
     */
    private fun showSessionError(message: String?) {
        Log.e(TAG, "❌ Voice session error: $message")
        // In production, show error dialog
        onNavigateBack?.invoke() ?: parentFragmentManager.popBackStack()
    }
    
    /**
     * Main voice chat UI
     */
    @Composable
    private fun VoiceChatScreen() {
        // Collect state from service
        val voiceState by voiceService?.voiceState?.collectAsState() ?: remember { mutableStateOf(VoiceState.LISTENING) }
        val transcript by voiceService?.transcript?.collectAsState() ?: remember { mutableStateOf("") }
        val micLevel by voiceService?.micLevel?.collectAsState() ?: remember { mutableStateOf(0.0f) }
        val assistantLevel by voiceService?.assistantLevel?.collectAsState() ?: remember { mutableStateOf(0.0f) }
        val isConnected by voiceService?.isConnected?.collectAsState() ?: remember { mutableStateOf(false) }
        val error by voiceService?.error?.collectAsState() ?: remember { mutableStateOf<String?>(null) }
        val currentPermit by voiceService?.currentPermitContext?.collectAsState() ?: remember { mutableStateOf<Permit?>(null) }
        
        // Check if hands-free (connected means we're in automatic conversation mode)
        val isHandsFree by remember(isConnected, voiceState) { 
            derivedStateOf { 
                isConnected || voiceState in listOf(VoiceState.CONNECTING, VoiceState.THINKING, VoiceState.SPEAKING)
            } 
        }
        
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(VoiceChatColors.Background)
        ) {
            // Main content
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Top section with close button
                TopSection(
                    onClose = {
                        stopVoiceSession()
                        // Use Compose navigation callback if available, otherwise fall back to fragment navigation
                        onNavigateBack?.invoke() ?: parentFragmentManager.popBackStack()
                    }
                )
                
                // Center pulsator
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        // Main pulsator
                        AdaptiveVoicePulsator(
                            voiceState = voiceState.name,
                            micLevel = micLevel,
                            assistantLevel = assistantLevel,
                            isConnected = isConnected,
                            size = 160.dp
                        )
                        
                        Spacer(modifier = Modifier.height(32.dp))
                        
                        // Voice state indicator
                        VoiceStateIndicator(
                            state = voiceState.name,
                            isConnected = isConnected
                        )
                        
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        // Transcript display
                        TranscriptDisplay(
                            transcript = transcript,
                            isHandsFree = isHandsFree,
                            modifier = Modifier.padding(horizontal = 24.dp)
                        )
                    }
                }
                
                // Bottom section
                BottomSection(
                    isHandsFree = isHandsFree,
                    voiceState = voiceState,
                    isConnected = isConnected,
                    error = error,
                    currentPermit = currentPermit,
                    onClearError = {
                        // Clear error in service
                    }
                )
            }
        }
    }
    
    /**
     * Top section with close button
     */
    @Composable
    private fun TopSection(onClose: () -> Unit) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            contentAlignment = Alignment.TopEnd
        ) {
            IconButton(
                onClick = onClose,
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.2f))
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Close voice chat",
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
    
    /**
     * Bottom section with controls and status
     */
    @Composable
    private fun BottomSection(
        isHandsFree: Boolean,
        voiceState: VoiceState,
        isConnected: Boolean,
        error: String?,
        currentPermit: Permit?,
        onClearError: () -> Unit
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Error display
            error?.let { errorMessage ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.Red.copy(alpha = 0.9f))
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Text(
                            text = "Error",
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = errorMessage,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        TextButton(
                            onClick = onClearError,
                            colors = ButtonDefaults.textButtonColors(contentColor = Color.White)
                        ) {
                            Text("Dismiss")
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
            
            // Current permit indicator
            if (currentPermit != null) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = VoiceChatColors.SecondaryBlue.copy(alpha = 0.2f)
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Description,
                            contentDescription = "Current permit",
                            tint = VoiceChatColors.SecondaryBlue,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Discussing: ${currentPermit.permitNumber}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = "${currentPermit.state} • ${currentPermit.destination ?: "No destination"}",
                                style = MaterialTheme.typography.bodySmall,
                                color = VoiceChatColors.TextSecondary
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
            }
            
            // Hands-free indicator
            if (isHandsFree) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(Color.Green)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Hands-free mode active",
                        style = MaterialTheme.typography.bodySmall,
                        color = VoiceChatColors.TextSecondary
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
            
            // Instructions (simplified, no tap to talk messaging)
            Text(
                text = when {
                    voiceState == VoiceState.CONNECTING -> "Connecting to AI dispatch..."
                    voiceState == VoiceState.SPEAKING -> "AI is speaking"
                    voiceState == VoiceState.THINKING -> "AI is thinking..."
                    isConnected -> "Ask me anything"
                    else -> "Initializing voice connection..."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = VoiceChatColors.TextSecondary,
                fontSize = 14.sp
            )
        }
    }
}