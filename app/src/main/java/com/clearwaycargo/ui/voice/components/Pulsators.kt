package com.clearwaycargo.ui.voice.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.sin

/**
 * Pulsing animation speeds for different voice states
 */
enum class PulseSpeed(val durationMs: Int) {
    SLOW(1200),    // Thinking state
    MEDIUM(800),   // Listening state  
    FAST(600)      // Speaking state - made smoother
}

/**
 * Voice chat colors
 */
object VoiceChatColors {
    val Background = Color(0xFF033249)  // Dark blue background
    val PrimaryOrange = Color(0xFFFF8038) // Clearway Cargo orange
    val SecondaryBlue = Color(0xFF2196F3) // Blue for outlines/depth
    val TextLight = Color(0xFFFFFFFF)
    val TextSecondary = Color(0xFFB0B0B0)
}

/**
 * Microphone pulsator for listening state
 * Shows medium pulse with microphone level indication
 */
@Composable
fun MicPulsator(
    level: Float,
    active: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = 120.dp
) {
    val infiniteTransition = rememberInfiniteTransition(label = "mic_pulse")
    
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = if (active) 1.4f else 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(PulseSpeed.MEDIUM.durationMs, easing = EaseInOutCubic),
            repeatMode = RepeatMode.Reverse
        ),
        label = "mic_scale"
    )
    
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.8f,
        targetValue = if (active) 0.3f else 0.8f,
        animationSpec = infiniteRepeatable(
            animation = tween(PulseSpeed.MEDIUM.durationMs, easing = EaseInOutCubic),
            repeatMode = RepeatMode.Reverse
        ),
        label = "mic_alpha"
    )
    
    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center
    ) {
        // Outer pulsing ring
        if (active) {
            Box(
                modifier = Modifier
                    .size(size)
                    .scale(pulseScale)
                    .alpha(pulseAlpha)
                    .clip(CircleShape)
                    .background(VoiceChatColors.PrimaryOrange)
            )
        }
        
        // Blue outline ring for depth
        Box(
            modifier = Modifier
                .size(size * 0.65f)
                .clip(CircleShape)
                .background(VoiceChatColors.SecondaryBlue.copy(alpha = 0.3f))
        )
        
        // Inner circle with level indication
        val innerScale = 1.0f + (level * 0.3f) // Scale based on mic level
        Box(
            modifier = Modifier
                .size(size * 0.6f)
                .scale(innerScale)
                .clip(CircleShape)
                .background(VoiceChatColors.PrimaryOrange)
        )
        
        // Microphone indicator (simple circle)
        Box(
            modifier = Modifier
                .size(16.dp)
                .clip(CircleShape)
                .background(VoiceChatColors.TextLight)
        )
    }
}

/**
 * Assistant pulsator for thinking/speaking states
 * Shows different speeds based on state
 */
@Composable
fun AssistantPulsator(
    level: Float,
    speed: PulseSpeed,
    modifier: Modifier = Modifier,
    size: Dp = 120.dp
) {
    val infiniteTransition = rememberInfiniteTransition(label = "assistant_pulse")
    
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.4f,
        animationSpec = infiniteRepeatable(
            animation = tween(speed.durationMs, easing = EaseInOutCubic),
            repeatMode = RepeatMode.Reverse
        ),
        label = "assistant_scale"
    )
    
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.8f,
        targetValue = 0.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(speed.durationMs, easing = EaseInOutCubic),
            repeatMode = RepeatMode.Reverse
        ),
        label = "assistant_alpha"
    )
    
    // Create multiple pulse rings for speaking effect
    val pulseScale2 by infiniteTransition.animateFloat(
        initialValue = 1.1f,
        targetValue = 1.6f,
        animationSpec = infiniteRepeatable(
            animation = tween((speed.durationMs * 1.2).toInt(), easing = EaseInOutCubic),
            repeatMode = RepeatMode.Reverse
        ),
        label = "assistant_scale2"
    )
    
    val pulseAlpha2 by infiniteTransition.animateFloat(
        initialValue = 0.6f,
        targetValue = 0.1f,
        animationSpec = infiniteRepeatable(
            animation = tween((speed.durationMs * 1.2).toInt(), easing = EaseInOutCubic),
            repeatMode = RepeatMode.Reverse
        ),
        label = "assistant_alpha2"
    )

    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center
    ) {
        // Multiple pulsing rings for more dramatic effect
        if (speed == PulseSpeed.FAST) {
            // Outer ring (largest, most faded)
            Box(
                modifier = Modifier
                    .size(size * 1.2f)
                    .scale(pulseScale2)
                    .alpha(pulseAlpha2)
                    .clip(CircleShape)
                    .background(VoiceChatColors.PrimaryOrange)
            )
        }
        
        // Primary pulsing ring
        Box(
            modifier = Modifier
                .size(size)
                .scale(pulseScale)
                .alpha(pulseAlpha)
                .clip(CircleShape)
                .background(VoiceChatColors.PrimaryOrange)
        )
        
        // Blue outline ring for depth
        Box(
            modifier = Modifier
                .size(size * 0.65f)
                .clip(CircleShape)
                .background(VoiceChatColors.SecondaryBlue.copy(alpha = 0.3f))
        )
        
        // Inner circle with assistant level indication and subtle pulse
        val innerPulse by infiniteTransition.animateFloat(
            initialValue = 1.0f,
            targetValue = 1.1f,
            animationSpec = infiniteRepeatable(
                animation = tween(speed.durationMs / 2, easing = EaseInOutCubic),
                repeatMode = RepeatMode.Reverse
            ),
            label = "inner_pulse"
        )
        
        val innerScale = (1.0f + (level * 0.4f)) * if (speed == PulseSpeed.FAST) innerPulse else 1.0f
        Box(
            modifier = Modifier
                .size(size * 0.6f)
                .scale(innerScale)
                .clip(CircleShape)
                .background(VoiceChatColors.PrimaryOrange)
        )
        
        // Assistant indicator (simple circle with different sizes for state)
        Box(
            modifier = Modifier
                .size(when (speed) {
                    PulseSpeed.SLOW -> 12.dp    // Thinking - smaller
                    PulseSpeed.FAST -> 20.dp    // Speaking - larger
                    else -> 16.dp              // Default
                })
                .clip(CircleShape)
                .background(VoiceChatColors.TextLight)
        )
    }
}

/**
 * Advanced waveform pulsator with multiple rings
 * Shows more sophisticated audio visualization
 */
@Composable
fun WaveformPulsator(
    micLevel: Float,
    assistantLevel: Float,
    isListening: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = 160.dp
) {
    val infiniteTransition = rememberInfiniteTransition(label = "waveform_pulse")
    
    // Multiple ring animations with different phases
    val ring1Scale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.8f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = EaseInOut),
            repeatMode = RepeatMode.Reverse
        ),
        label = "ring1_scale"
    )
    
    val ring2Scale by infiniteTransition.animateFloat(
        initialValue = 1.2f,
        targetValue = 2.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = EaseInOut, delayMillis = 200),
            repeatMode = RepeatMode.Reverse
        ),
        label = "ring2_scale"
    )
    
    val ring3Scale by infiniteTransition.animateFloat(
        initialValue = 1.4f,
        targetValue = 2.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = EaseInOut, delayMillis = 400),
            repeatMode = RepeatMode.Reverse
        ),
        label = "ring3_scale"
    )
    
    Canvas(
        modifier = modifier.size(size)
    ) {
        val center = Offset(size.toPx() / 2, size.toPx() / 2)
        val baseRadius = size.toPx() * 0.15f
        
        // Outer rings (multiple layers)
        drawPulsingRing(center, baseRadius * ring3Scale, 0.1f, VoiceChatColors.PrimaryOrange)
        drawPulsingRing(center, baseRadius * ring2Scale, 0.2f, VoiceChatColors.PrimaryOrange)
        drawPulsingRing(center, baseRadius * ring1Scale, 0.3f, VoiceChatColors.PrimaryOrange)
        
        // Central circle (responsive to audio level)
        val level = if (isListening) micLevel else assistantLevel
        val centerRadius = baseRadius * (0.8f + level * 0.6f)
        
        drawCircle(
            color = VoiceChatColors.PrimaryOrange,
            radius = centerRadius,
            center = center
        )
    }
}

/**
 * Helper function to draw a pulsing ring
 */
private fun DrawScope.drawPulsingRing(
    center: Offset,
    radius: Float,
    alpha: Float,
    color: Color
) {
    drawCircle(
        color = color.copy(alpha = alpha),
        radius = radius,
        center = center,
        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3.dp.toPx())
    )
}

/**
 * Transcript display component
 * Shows current transcript with proper truncation
 */
@Composable
fun TranscriptDisplay(
    transcript: String,
    isHandsFree: Boolean,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = transcript.ifBlank { 
                if (isHandsFree) "" else "" 
            },
            style = MaterialTheme.typography.bodyLarge,
            color = if (transcript.isBlank()) {
                VoiceChatColors.TextSecondary
            } else {
                VoiceChatColors.TextLight
            },
            textAlign = TextAlign.Center,
            maxLines = if (isHandsFree) 1 else 3,
            overflow = TextOverflow.Ellipsis,
            fontWeight = if (transcript.isBlank()) FontWeight.Normal else FontWeight.Medium
        )
    }
}

/**
 * Voice state indicator
 * Shows current state with appropriate styling
 */
@Composable
fun VoiceStateIndicator(
    state: String,
    isConnected: Boolean,
    modifier: Modifier = Modifier
) {
    val stateColor = when {
        !isConnected -> Color.Red
        state == "LISTENING" -> Color.Green
        state == "THINKING" -> Color.Yellow
        state == "SPEAKING" -> Color.Blue
        else -> VoiceChatColors.TextSecondary
    }
    
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(stateColor)
        )
        
        Spacer(modifier = Modifier.width(8.dp))
        
        Text(
            text = when {
                !isConnected -> "Connecting..."
                isConnected -> "Connected"
                else -> state.lowercase().replaceFirstChar { it.uppercase() }
            },
            style = MaterialTheme.typography.labelMedium,
            color = VoiceChatColors.TextSecondary,
            fontWeight = FontWeight.Medium
        )
    }
}

/**
 * Combined voice chat pulsator that adapts to current state
 */
@Composable
fun AdaptiveVoicePulsator(
    voiceState: String,
    micLevel: Float,
    assistantLevel: Float,
    isConnected: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = 140.dp
) {
    when (voiceState.uppercase()) {
        "LISTENING" -> {
            MicPulsator(
                level = micLevel,
                active = isConnected,
                modifier = modifier,
                size = size
            )
        }
        "THINKING" -> {
            AssistantPulsator(
                level = 0.0f,
                speed = PulseSpeed.SLOW,
                modifier = modifier,
                size = size
            )
        }
        "SPEAKING" -> {
            AssistantPulsator(
                level = assistantLevel,
                speed = PulseSpeed.FAST,
                modifier = modifier,
                size = size
            )
        }
        else -> {
            // Default state (disconnected or error)
            Box(
                modifier = modifier
                    .size(size)
                    .clip(CircleShape)
                    .background(VoiceChatColors.TextSecondary.copy(alpha = 0.3f)),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(VoiceChatColors.TextSecondary)
                )
            }
        }
    }
}