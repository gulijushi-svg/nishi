package com.fengkao.timetable.ui.service

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import com.fengkao.timetable.R
import io.github.g00fy2.quickie.QRResult
import io.github.g00fy2.quickie.ScanCustomCode
import io.github.g00fy2.quickie.config.BarcodeFormat
import io.github.g00fy2.quickie.config.ScannerConfig

@Composable
fun rememberDeviceQrScanner(
    onCode: (String) -> Unit,
    onError: (String) -> Unit,
    onPermissionDenied: () -> Unit,
): () -> Unit {
    val context = LocalContext.current
    val currentOnCode by rememberUpdatedState(onCode)
    val currentOnError by rememberUpdatedState(onError)
    val currentOnPermissionDenied by rememberUpdatedState(onPermissionDenied)
    val config = remember {
        ScannerConfig.build {
            setBarcodeFormats(listOf(BarcodeFormat.QR_CODE))
            setOverlayStringRes(R.string.quickie_device_scan_prompt)
            setHorizontalFrameRatio(1f)
            setHapticSuccessFeedback(true)
            setShowTorchToggle(true)
            setShowCloseButton(true)
            setKeepScreenOn(true)
        }
    }
    lateinit var scannerLauncher: ActivityResultLauncher<ScannerConfig>
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) scannerLauncher.launch(config)
        else currentOnPermissionDenied()
    }
    scannerLauncher = rememberLauncherForActivityResult(ScanCustomCode()) { result ->
        when (result) {
            is QRResult.QRSuccess -> {
                val raw = result.content.rawValue
                if (raw.isNullOrBlank()) currentOnError("未识别到二维码内容，请重试")
                else currentOnCode(raw)
            }
            QRResult.QRUserCanceled -> Unit
            QRResult.QRMissingPermission -> {
                currentOnError("需要相机权限才能扫码，也可手动输入设备编号")
                permissionLauncher.launch(Manifest.permission.CAMERA)
            }
            is QRResult.QRError -> currentOnError("扫码失败，请调整距离和光线后重试，也可手动输入设备编号")
        }
    }
    return remember(context, scannerLauncher, permissionLauncher, config) {
        {
            if (context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                scannerLauncher.launch(config)
            } else {
                permissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }
    }
}
