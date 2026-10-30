package com.lumenarcanus.dialife.feature.weblogin

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.lumenarcanus.dialife.liveRepository

/** Web login with its data; the camera scanner is the platform's. */
@Composable
fun WebLoginRoute(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val vm = viewModel { WebLoginViewModel(WebLoginRepository({ ctx.liveRepository.credentials })) }
    val uiState by vm.uiState.collectAsStateWithLifecycle()
    fun scan() {
        val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
        GmsBarcodeScanning.getClient(ctx, options).startScan().addOnSuccessListener { b -> vm.scanned(b.rawValue.orEmpty()) }
    }
    LaunchedEffect(Unit) { scan() } // opened to scan: go straight to the camera
    LaunchedEffect(vm) { vm.keepSessionsFresh() }
    BackHandler(onBack = onBack)
    WebLoginScreen(uiState, onScan = ::scan, onDecide = vm::decide, onRevoke = vm::revoke, onBack = onBack)
}
