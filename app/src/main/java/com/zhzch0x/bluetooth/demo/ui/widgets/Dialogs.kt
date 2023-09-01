package com.zhzch0x.bluetooth.demo.ui.widgets

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun LoadingDialog(show: MutableState<Boolean>) = AnimatedVisibility(show.value,
    enter = fadeIn(), exit=fadeOut()) {
    Box(Modifier.fillMaxSize()) {
        Surface(Modifier.align(Alignment.Center), RoundedCornerShape(8.dp), shadowElevation = 4.dp) {
            CircularProgressIndicator(
                Modifier
                    .padding(36.dp)
                    .size(48.dp))
        }
    }
}

@Composable
fun ProgressDialog(show: MutableState<Boolean>, progress: Float) = AnimatedVisibility(show.value,
    enter = fadeIn(), exit=fadeOut()) {
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.align(Alignment.Center).padding(start = 36.dp, end = 36.dp)
            .shadow(4.dp, RoundedCornerShape(8.dp)).background(Color.White)
            .padding(24.dp)) {
            LinearProgressIndicator(progress, Modifier.fillMaxWidth())
        }
    }
}