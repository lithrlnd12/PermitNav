package com.permitnav.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.permitnav.R
import com.permitnav.ui.viewmodels.AuthViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuthScreen(
    onAuthSuccess: () -> Unit,
    modifier: Modifier = Modifier
) {
    val viewModel: AuthViewModel = viewModel()
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    
    var isSignUp by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var confirmPasswordVisible by remember { mutableStateOf(false) }
    
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Logo
        Image(
            painter = painterResource(id = R.mipmap.ic_launcher),
            contentDescription = "ClearwayCargo Logo",
            modifier = Modifier.size(160.dp) // Made bigger as requested
        )
        
        Spacer(modifier = Modifier.height(24.dp))
        
        // Title
        Text(
            text = "Clearway Cargo",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        
        Text(
            text = "Navigating Cargo, The Clear Way",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        
        Spacer(modifier = Modifier.height(32.dp))
        
        // Auth Form Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = if (isSignUp) "Create Account" else "Welcome Back",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                
                Spacer(modifier = Modifier.height(24.dp))
                
                // Email Field
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text("Email") },
                    leadingIcon = { Icon(Icons.Default.Email, contentDescription = null) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // Password Field
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                if (passwordVisible) Icons.Default.Visibility 
                                else Icons.Default.VisibilityOff,
                                contentDescription = if (passwordVisible) "Hide password" else "Show password"
                            )
                        }
                    },
                    visualTransformation = if (passwordVisible) VisualTransformation.None 
                                         else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                
                // Confirm Password Field (only for sign up)
                if (isSignUp) {
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    OutlinedTextField(
                        value = confirmPassword,
                        onValueChange = { confirmPassword = it },
                        label = { Text("Confirm Password") },
                        leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
                        trailingIcon = {
                            IconButton(onClick = { confirmPasswordVisible = !confirmPasswordVisible }) {
                                Icon(
                                    if (confirmPasswordVisible) Icons.Default.Visibility 
                                    else Icons.Default.VisibilityOff,
                                    contentDescription = if (confirmPasswordVisible) "Hide password" else "Show password"
                                )
                            }
                        },
                        visualTransformation = if (confirmPasswordVisible) VisualTransformation.None 
                                             else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        isError = isSignUp && confirmPassword.isNotEmpty() && password != confirmPassword
                    )
                }
                
                Spacer(modifier = Modifier.height(24.dp))
                
                // Error Message
                uiState.error?.let { error ->
                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )
                }
                
                // Sign In/Up Button
                Button(
                    onClick = {
                        if (isSignUp) {
                            if (password == confirmPassword) {
                                viewModel.signUp(email, password, onAuthSuccess)
                            }
                        } else {
                            viewModel.signIn(email, password, onAuthSuccess)
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    enabled = !uiState.isLoading && 
                             email.isNotBlank() && 
                             password.isNotBlank() &&
                             (!isSignUp || password == confirmPassword),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    if (uiState.isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    } else {
                        Text(
                            text = if (isSignUp) "Create Account" else "Sign In",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(24.dp))
                
                // Toggle Sign In/Up
                TextButton(
                    onClick = { 
                        isSignUp = !isSignUp
                        // Clear form when switching
                        email = ""
                        password = ""
                        confirmPassword = ""
                        viewModel.clearError()
                    }
                ) {
                    Text(
                        text = if (isSignUp) "Already have an account? Sign In" 
                               else "Don't have an account? Sign Up",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}