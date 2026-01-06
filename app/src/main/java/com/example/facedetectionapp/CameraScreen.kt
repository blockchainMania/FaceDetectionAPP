package com.example.facedetectionapp

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect
import android.util.Log
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.util.concurrent.Executors

data class FaceResult(val rect: Rect, val name: String, val distance: Float)

@OptIn(ExperimentalGetImage::class, ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun CameraScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val keyboardController = LocalSoftwareKeyboardController.current

    // UI 상태
    var faceResults by remember { mutableStateOf<List<FaceResult>>(emptyList()) }
    var currentFaceBitmap by remember { mutableStateOf<Bitmap?>(null) } // 현재 캡처된 얼굴

    // 사용자 입력 상태
    var inputName by remember { mutableStateOf("") }

    // ★ 등록된 얼굴 데이터베이스 (이름 -> 벡터 리스트)
    // 앱을 껐다 켜면 초기화되므로, 실제 앱에선 Room DB 등에 저장해야 함
    val registeredFaces = remember { mutableStateMapOf<String, List<FloatArray>>() }

    val classifier = remember { FaceClassifier(context) }
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }

    Box(modifier = Modifier.fillMaxSize()) {
        // 1. 카메라 프리뷰 & 분석 로직
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                val previewView = PreviewView(ctx)
                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)

                cameraProviderFuture.addListener({
                    val cameraProvider = cameraProviderFuture.get()
                    val preview = Preview.Builder().build()
                    preview.setSurfaceProvider(previewView.surfaceProvider)

                    val imageAnalysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()

                    val fastOptions = FaceDetectorOptions.Builder()
                        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                        .build()
                    val detector = FaceDetection.getClient(fastOptions)

                    imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                        val mediaImage = imageProxy.image
                        if (mediaImage != null) {
                            val inputImage = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

                            detector.process(inputImage)
                                .addOnSuccessListener { faces ->
                                    val results = mutableListOf<FaceResult>()
                                    val frameBitmap = imageProxy.toBitmap()
                                    val rotation = imageProxy.imageInfo.rotationDegrees.toFloat()
                                    val rotatedFrame = if (rotation != 0f) rotateBitmap(frameBitmap, rotation) else frameBitmap

                                    // 화면에 얼굴이 1개일 때만 등록용 비트맵 갱신 (여러 명이면 헷갈리니까)
                                    if (faces.size == 1) {
                                        val face = faces[0]
                                        val rect = face.boundingBox
                                        val safeX = rect.left.coerceAtLeast(0)
                                        val safeY = rect.top.coerceAtLeast(0)
                                        val safeWidth = rect.width().coerceAtMost(rotatedFrame.width - safeX)
                                        val safeHeight = rect.height().coerceAtMost(rotatedFrame.height - safeY)

                                        if (safeWidth > 0 && safeHeight > 0) {
                                            currentFaceBitmap = Bitmap.createBitmap(rotatedFrame, safeX, safeY, safeWidth, safeHeight)
                                        }
                                    } else {
                                        currentFaceBitmap = null
                                    }

                                    // 인식 로직
                                    for (face in faces) {
                                        val rect = face.boundingBox
                                        val safeX = rect.left.coerceAtLeast(0)
                                        val safeY = rect.top.coerceAtLeast(0)
                                        val safeWidth = rect.width().coerceAtMost(rotatedFrame.width - safeX)
                                        val safeHeight = rect.height().coerceAtMost(rotatedFrame.height - safeY)

                                        if (safeWidth > 0 && safeHeight > 0) {
                                            val croppedFace = Bitmap.createBitmap(rotatedFrame, safeX, safeY, safeWidth, safeHeight)
                                            val currentEmbedding = classifier.getFaceEmbedding(croppedFace)

                                            var bestMatchName = "Unknown"
                                            var globalMinDistance = Float.MAX_VALUE

                                            // 등록된 모든 사람과 비교 (1:N 매칭)
                                            for ((name, vectors) in registeredFaces) {
                                                for (vector in vectors) {
                                                    val distance = classifier.calculateDistance(currentEmbedding, vector)
                                                    if (distance < globalMinDistance) {
                                                        globalMinDistance = distance
                                                    }
                                                }
                                                // ★ 임계값 0.85 (보안/편의성 밸런스)
                                                if (globalMinDistance < 0.85f) {
                                                    bestMatchName = name
                                                }
                                            }
                                            results.add(FaceResult(rect, bestMatchName, globalMinDistance))
                                        }
                                    }
                                    faceResults = results
                                }
                                .addOnCompleteListener { imageProxy.close() }
                        } else {
                            imageProxy.close()
                        }
                    }

                    try {
                        cameraProvider.unbindAll()
                        cameraProvider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_FRONT_CAMERA, preview, imageAnalysis)
                    } catch (e: Exception) { e.printStackTrace() }
                }, ContextCompat.getMainExecutor(ctx))
                previewView
            }
        )

        // 2. 오버레이 (박스 그리기)
        Canvas(modifier = Modifier.fillMaxSize()) {
            val scaleX = size.width / 480f
            val scaleY = size.height / 640f

            for (result in faceResults) {
                val rect = result.rect
                val left = size.width - (rect.right * scaleX)
                val right = size.width - (rect.left * scaleX)
                val top = rect.top * scaleY
                val bottom = rect.bottom * scaleY

                drawRect(
                    color = if (result.name == "Unknown") Color.Red else Color.Green,
                    topLeft = Offset(left, top),
                    size = Size(right - left, bottom - top),
                    style = Stroke(width = 8f)
                )

                drawContext.canvas.nativeCanvas.apply {
                    val paint = android.graphics.Paint().apply {
                        color = android.graphics.Color.WHITE
                        textSize = 50f
                        isFakeBoldText = true
                        setShadowLayer(5f, 0f, 0f, android.graphics.Color.BLACK)
                    }
                    drawText("${result.name} (${String.format("%.2f", result.distance)})", left, top - 20f, paint)
                }
            }
        }

        // 3. ★ 등록 UI 패널 (화면 하단)
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.6f))
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 현재 등록된 사진 개수 확인
            val currentCount = registeredFaces[inputName]?.size ?: 0

            Text(
                text = if (inputName.isEmpty()) "이름을 입력하세요" else "'$inputName' 사진: $currentCount / 5",
                color = Color.White,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 이름 입력 필드
                TextField(
                    value = inputName,
                    onValueChange = { inputName = it },
                    placeholder = { Text("등록할 이름 (예: Me)") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions.Default.copy(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { keyboardController?.hide() })
                )

                Spacer(modifier = Modifier.width(8.dp))

                // 등록 버튼
                Button(
                    onClick = {
                        if (inputName.isBlank()) {
                            Toast.makeText(context, "이름을 먼저 입력하세요!", Toast.LENGTH_SHORT).show()
                            return@Button
                        }

                        if (currentCount >= 5) {
                            Toast.makeText(context, "최대 5장까지만 등록 가능합니다.", Toast.LENGTH_SHORT).show()
                            return@Button
                        }

                        val bitmap = currentFaceBitmap
                        if (bitmap != null) {
                            val embedding = classifier.getFaceEmbedding(bitmap)

                            // 리스트에 추가
                            val currentList = registeredFaces[inputName]?.toMutableList() ?: mutableListOf()
                            currentList.add(embedding)
                            registeredFaces[inputName] = currentList

                            Toast.makeText(context, "$inputName 등록됨! (${currentList.size}/5)", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "얼굴을 찾을 수 없습니다.", Toast.LENGTH_SHORT).show()
                        }
                    },
                    // 얼굴이 감지 안 됐거나 이름이 없으면 버튼 비활성화 시각효과
                    enabled = currentFaceBitmap != null && inputName.isNotBlank()
                ) {
                    Text("추가 (+)")
                }
            }

            // 등록 데이터 초기화 버튼 (선택사항)
            if (registeredFaces.isNotEmpty()) {
                TextButton(onClick = { registeredFaces.clear() }) {
                    Text("모든 데이터 초기화", color = Color.Red)
                }
            }
        }
    }
}

fun rotateBitmap(bitmap: Bitmap, degrees: Float): Bitmap {
    val matrix = Matrix()
    matrix.postRotate(degrees)
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
}