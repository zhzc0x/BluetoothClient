package com.zhzch0x.bluetooth.demo.ui

import android.content.Intent
import android.os.Bundle
import android.os.Looper
import android.os.PersistableBundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.snackbar.BaseTransientBottomBar
import com.zhzch0x.bluetooth.demo.ui.theme.BluetoothClientTheme
import com.zhzch0x.bluetooth.demo.ui.widgets.LoadingDialog
import kotlinx.coroutines.launch


abstract class ComposeBaseActivity: ComponentActivity() {

    open var showLoading = mutableStateOf(false)
    private var snackBarState = SnackbarHostState()
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if(intent != null){
            handleIntent(intent)
        }
        initData()
        setContent {
            BluetoothClientTheme {
                Content()
                LoadingDialog(showLoading)
                SnackbarHost(hostState = snackBarState){
                    Snackbar(it)
                }
            }
        }
    }

    protected open fun handleIntent(intent: Intent){}
    
    protected open fun initData(){}

    @Composable
    protected abstract fun Content()

    open fun showSnackBar(message: String,
                     actionLabel: String? = null,
                     withDismissAction: Boolean = true,
                     duration: SnackbarDuration =
                         if (actionLabel == null) SnackbarDuration.Short else SnackbarDuration.Indefinite){
        lifecycleScope.launch {
            snackBarState.showSnackbar(message, actionLabel, withDismissAction, duration)
        }
    }

    open fun showToast(msg: String){
        runOnUiThread {
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)

    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
    }

    override fun onDestroy() {
        super.onDestroy()
        showLoading.value = false
    }
    
}