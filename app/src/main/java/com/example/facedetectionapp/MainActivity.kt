package com.example.facedetectionapp

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    // 권한 요청 결과 처리 (승인되면 카메라 화면 표시)
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        shouldShowCamera.value = isGranted
    }

    // 카메라 화면 표시 여부 상태
    private val shouldShowCamera = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 앱 시작 시 권한 체크
        checkCameraPermission()

        setContent {
            // fillMaxSize()로 화면 전체를 사용
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (shouldShowCamera.value) {
                    // 권한이 있으면 카메라 메인 화면 표시
                    CameraScreen()
                } else {
                    // 권한이 없으면 안내 문구와 요청 버튼 표시
                    PermissionRationaleUI()
                }
            }
        }
    }

    private fun checkCameraPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED) {
            shouldShowCamera.value = true
        } else {
            // 권한이 없으면 바로 요청 팝업 띄우기
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    // 권한 필요 안내 UI 컴포저블
    @Composable
    fun PermissionRationaleUI() {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = "얼굴 인식을 위해 카메라 권한이 꼭 필요합니다.",
                modifier = Modifier.padding(bottom = 16.dp)
            )
            Button(
                onClick = {
                    // 버튼 누르면 다시 권한 요청 팝업 띄우기
                    requestPermissionLauncher.launch(Manifest.permission.CAMERA)
                }
            ) {
                Text("권한 허용하기")
            }
        }
    }
}