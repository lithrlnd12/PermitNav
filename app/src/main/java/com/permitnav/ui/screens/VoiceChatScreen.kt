package com.permitnav.ui.screens

import android.view.ViewGroup
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentContainerView
import androidx.fragment.app.FragmentTransaction
import com.clearwaycargo.ui.voice.VoiceChatFragment

/**
 * Compose wrapper for VoiceChatFragment
 */
@Composable
fun VoiceChatScreen(
    permitId: String? = null,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val fragmentManager = remember(context) {
        (context as FragmentActivity).supportFragmentManager
    }
    
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            FragmentContainerView(context).apply {
                id = android.view.View.generateViewId()
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
        },
        update = { containerView ->
            val existingFragment = fragmentManager.findFragmentById(containerView.id)
            if (existingFragment == null) {
                fragmentManager.beginTransaction().apply {
                    val fragment = if (permitId != null) {
                        VoiceChatFragment.newInstance(permitId, onNavigateBack)
                    } else {
                        VoiceChatFragment.newInstance(null, onNavigateBack)
                    }
                    replace(containerView.id, fragment)
                    commit()
                }
            }
        }
    )
    
    DisposableEffect(Unit) {
        onDispose {
            // Clean up fragment when leaving
            val fragment = fragmentManager.fragments.firstOrNull { it is VoiceChatFragment }
            if (fragment != null) {
                fragmentManager.beginTransaction().apply {
                    remove(fragment)
                    commit()
                }
            }
        }
    }
}