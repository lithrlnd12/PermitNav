package com.permitnav.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.permitnav.R
import com.permitnav.ui.theme.PrimaryOrange
import com.permitnav.ui.theme.SecondaryBlue
import com.permitnav.ui.theme.TextSecondary
import kotlinx.coroutines.delay

@Composable
fun SplashScreen(
    onSplashComplete: () -> Unit
) {
    var startAnimation by remember { mutableStateOf(false) }
    val alphaAnim = animateFloatAsState(
        targetValue = if (startAnimation) 1f else 0f,
        animationSpec = tween(durationMillis = 1500),
        label = "splash_alpha"
    )
    
    LaunchedEffect(key1 = true) {
        startAnimation = true
        delay(2500)
        onSplashComplete()
    }
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(SecondaryBlue), // Dark Navy Blue background to match logo exactly
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .fillMaxSize()
                .alpha(alphaAnim.value)
        ) {
            // Smaller top spacer to move logo higher
            Spacer(modifier = Modifier.weight(0.6f))
            
            // Logo takes up most of the screen to show perfectly
            Image(
                painter = painterResource(id = R.drawable.clearway_cargo_logo),
                contentDescription = "Clearway Cargo Logo with tagline",
                modifier = Modifier
                    .fillMaxWidth(0.95f) // Use 95% of screen width (bigger as requested)
                    .aspectRatio(1f), // Keep square aspect ratio
                contentScale = ContentScale.Fit
            )
            
            // Larger bottom spacer to balance the layout
            Column(
                modifier = Modifier.weight(1.4f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Bottom
            ) {
                Text(
                    text = "Powered by HERE Maps & AI",
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = TextSecondary.copy(alpha = 0.7f)
                    ),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(bottom = 32.dp)
                )
            }
        }
    }
}