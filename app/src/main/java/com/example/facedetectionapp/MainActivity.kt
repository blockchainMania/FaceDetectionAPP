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
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.facedetectionapp.ui.PersonListScreen // 패키지 경로 확인!

class MainActivity : ComponentActivity() {

    // 권한 요청 결과 처리
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        shouldShowCamera.value = isGranted
    }

    // 카메라 권한 상태
    private val shouldShowCamera = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 앱 시작 시 권한 상태 한 번 체크 (요청은 안 함, 상태만 확인)
        checkPermissionStatus()

        setContent {
            // 1. 네비게이션 컨트롤러 생성 (화면 이동 관리자)
            val navController = rememberNavController()

            // 2. 네비게이션 호스트 (화면 갈아끼우는 틀)
            // startDestination = "list" -> 앱 켜자마자 목록 화면부터 보여줌
            NavHost(navController = navController, startDestination = "list") {

                // [화면 1] 목록 화면 (PersonListScreen)
                composable("list") {
                    PersonListScreen(
                        onAddClick = {
                            // + 버튼을 누르면 "camera" 화면으로 이동!
                            navController.navigate("camera")
                        }
                    )
                }

                // [화면 2] 카메라 화면 (CameraScreen)
                composable("camera") {
                    // 여기 들어왔을 때 권한을 다시 확인
                    if (shouldShowCamera.value) {
                        // 권한 있음 -> 카메라 실행
                        CameraScreen()
                    } else {
                        // 권한 없음 -> 권한 요청 UI 표시
                        PermissionRationaleUI()
                        // 화면에 들어오자마자 권한 팝업을 띄우고 싶다면 아래 주석 해제
                        // SideEffect { requestPermissionLauncher.launch(Manifest.permission.CAMERA) }
                    }
                }
            }
        }
    }

    private fun checkPermissionStatus() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED) {
            shouldShowCamera.value = true
        }
    }

    // 권한 필요 안내 UI
    @Composable
    fun PermissionRationaleUI() {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxSize().padding(16.dp)
        ) {
            Text(
                text = "새로운 인맥을 등록하려면\n카메라 권한이 필요합니다.",
                modifier = Modifier.padding(bottom = 16.dp)
            )
            Button(
                onClick = {
                    // 버튼 누르면 권한 요청 팝업 띄우기
                    requestPermissionLauncher.launch(Manifest.permission.CAMERA)
                }
            ) {
                Text("권한 허용하기")
            }
        }
    }
}