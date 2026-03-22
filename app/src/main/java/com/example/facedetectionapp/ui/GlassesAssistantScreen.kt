package com.example.facedetectionapp

import android.Manifest
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.facedetectionapp.data.PersonViewModel
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
fun GlassesAssistantScreen(
    viewModel: PersonViewModel = viewModel()
) {
    val context = LocalContext.current
    val registeredPeople by viewModel.allPeople.collectAsState()
    val scope = rememberCoroutineScope()

    // 상태 관리
    var isGlassesConnected by remember { mutableStateOf(false) }
    var lastRecognizedName by remember { mutableStateOf("대기 중...") }
    var lastRecognizedTime by remember { mutableStateOf(0L) }
    var permissionsGranted by remember { mutableStateOf(false) }

    // ML Kit 및 커스텀 얼굴 분류기
    val classifier = remember { FaceClassifier(context) }
    val detector = remember {
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .build()
        FaceDetection.getClient(options)
    }

    // 1. TTS (음성 비서) 초기화
    var tts by remember { mutableStateOf<TextToSpeech?>(null) }
    DisposableEffect(context) {
        val textToSpeech = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.KOREAN
            }
        }
        tts = textToSpeech
        onDispose { textToSpeech.shutdown() }
    }

    // 2. 귓속말 브리핑 함수
    fun speakToGlasses(name: String, info: String) {
        val currentTime = System.currentTimeMillis()
        // 쿨다운: 10초 이내에 같은 사람 중복 브리핑 방지
        if (name == lastRecognizedName && (currentTime - lastRecognizedTime) < 10000) {
            return
        }

        lastRecognizedName = name
        lastRecognizedTime = currentTime

        val speechText = "$name 님입니다. $info"
        tts?.speak(speechText, TextToSpeech.QUEUE_FLUSH, null, "glasses_briefing")

        Toast.makeText(context, "🗣️ 브리핑: $name", Toast.LENGTH_SHORT).show()
    }

    // 3. 메타 글라스에서 넘어온 사진 처리 함수
    fun processImageFromGlasses(glassesBitmap: Bitmap) {
        val inputImage = InputImage.fromBitmap(glassesBitmap, 0)

        detector.process(inputImage).addOnSuccessListener { faces ->
            if (faces.isEmpty()) return@addOnSuccessListener

            val face = faces[0]
            val rect = face.boundingBox

            val safeX = rect.left.coerceAtLeast(0)
            val safeY = rect.top.coerceAtLeast(0)
            val safeWidth = rect.width().coerceAtMost(glassesBitmap.width - safeX)
            val safeHeight = rect.height().coerceAtMost(glassesBitmap.height - safeY)

            if (safeWidth > 0 && safeHeight > 0) {
                val croppedFace = Bitmap.createBitmap(glassesBitmap, safeX, safeY, safeWidth, safeHeight)
                val currentEmbedding = classifier.getFaceEmbedding(croppedFace)

                var bestMatchName = "Unknown"
                var bestMatchInfo = ""
                var globalMinDistance = Float.MAX_VALUE

                for (person in registeredPeople) {
                    var localMinDistance = Float.MAX_VALUE
                    for (vector in person.embeddings) {
                        val distance = classifier.calculateDistance(currentEmbedding, vector)
                        if (distance < localMinDistance) localMinDistance = distance
                    }
                    if (localMinDistance < globalMinDistance) {
                        globalMinDistance = localMinDistance
                        bestMatchName = person.name
                        bestMatchInfo = person.info
                    }
                }

                if (globalMinDistance < 0.85f && bestMatchName != "Unknown") {
                    speakToGlasses(bestMatchName, bestMatchInfo)
                }
            }
        }
    }

    // ★ 4. 권한 요청 런처 (블루투스, 카메라 권한이 있어야 앱이 안 튕김)
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        permissionsGranted = permissions.values.all { it }
        if (!permissionsGranted) {
            Toast.makeText(context, "블루투스 및 카메라 권한이 필요합니다.", Toast.LENGTH_LONG).show()
        }
    }

    // 앱 시작 시 권한 묻기
    LaunchedEffect(Unit) {
        val permissionsToRequest = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissionsToRequest.add(Manifest.permission.BLUETOOTH_SCAN)
            permissionsToRequest.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        permissionLauncher.launch(permissionsToRequest.toTypedArray())
    }

    // ★ 5. 메타 글라스 블루투스 연결 로직 (권한이 허용된 후에만 실행)
    LaunchedEffect(permissionsGranted) {
        if (permissionsGranted) {
            try {
                // TODO: 깃허브에서 받은 Meta Wearables SDK의 실제 클래스명으로 임포트 수정 필요
                // val client = com.meta.wearable.WearableClient.getInstance(context)
                // val devices = client.scanForDevices()
                // if (devices.isNotEmpty()) {
                //     val myGlasses = devices.first()
                //     client.connect(myGlasses)
                //     isGlassesConnected = true
                //
                //     client.startCameraStream { incomingBitmap ->
                //         processImageFromGlasses(incomingBitmap)
                //     }
                // }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // ======================================================
    // UI Layout (주머니 속 스마트폰 대시보드 화면)
    // ======================================================
    Scaffold(
        containerColor = Color(0xFF121212)
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = if (isGlassesConnected) Icons.Filled.BluetoothConnected else Icons.Filled.BluetoothDisabled,
                contentDescription = null,
                tint = if (isGlassesConnected) Color(0xFF00E676) else Color.Gray,
                modifier = Modifier.size(80.dp)
            )
            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = if (isGlassesConnected) "메타 글라스 연동 중..." else "글라스 스캔 중...",
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(48.dp))

            // 테스트용 패널
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(Icons.Filled.RecordVoiceOver, null, tint = Color(0xFF2196F3), modifier = Modifier.size(40.dp))
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("마지막 인식 대상", color = Color.Gray, fontSize = 14.sp)
                    Text(lastRecognizedName, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)

                    Spacer(modifier = Modifier.height(24.dp))

                    Button(
                        onClick = {
                            speakToGlasses("테스트 계정", "오디오 연동 테스트 중입니다. 잘 들리시나요?")
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2196F3))
                    ) {
                        Text("🔊 음성 브리핑 강제 테스트")
                    }
                }
            }
        }
    }
}