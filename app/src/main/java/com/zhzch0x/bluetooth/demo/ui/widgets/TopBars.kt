package com.zhzch0x.bluetooth.demo.ui.widgets

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun TopBar(modifier: Modifier = Modifier
    .fillMaxWidth()
    .height(44.dp), title: String,
           showBackButton: Boolean = true, onBack: (() -> Unit)? = null,
           endRow: (@Composable RowScope.() -> Unit)? = null) = Surface(shadowElevation=2.5.dp) {
    Box(modifier) {
        if(showBackButton){
            IconButton(onClick=onBack!!, Modifier.align(Alignment.CenterStart)) {
                Icon(imageVector = Icons.Default.ArrowBack, contentDescription = "Back")
            }
        }
        Text(text = title, Modifier.align(Alignment.Center),
            fontSize = 18.sp, fontWeight= FontWeight.Medium, textAlign= TextAlign.Center)
        if(endRow != null){
            Row(Modifier.align(Alignment.CenterEnd), content = endRow)
        }
    }
}