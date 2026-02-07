package com.example.facedetectionapp

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect
import android.net.Uri
import android.provider.ContactsContract
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.facedetectionapp.data.Person
import com.example.facedetectionapp.data.PersonViewModel
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.util.concurrent.Executors
import kotlin.math.roundToInt

data class FaceResult(val rect: Rect, val name: String, val info: String, val distance: Float)

@OptIn(ExperimentalGetImage::class, ExperimentalMaterial3Api::class)
@Composable
fun CameraScreen(
    viewModel: PersonViewModel = viewModel()
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val density = LocalDensity.current

    val registeredPeople by viewModel.allPeople.collectAsState()

    var isRegistrationMode by remember { mutableStateOf(false) }
    var lensFacing by remember { mutableIntStateOf(CameraSelector.LENS_FACING_FRONT) }
    var faceResults by remember { mutableStateOf<List<FaceResult>>(emptyList()) }
    var currentFrameBitmap by remember { mutableStateOf<Bitmap?>(null) }

    var showInputDialog by remember { mutableStateOf(false) }
    var selectedFaceBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isEditingMode by remember { mutableStateOf(false) }
    var editingPersonId by remember { mutableIntStateOf(-1) }

    // [New] 전화번호 필드 추가
    var inputName by remember { mutableStateOf("") }
    var inputPhone by remember { mutableStateOf("") }
    var inputInfo by remember { mutableStateOf("") }

    var previewView by remember { mutableStateOf<PreviewView?>(null) }

    val classifier = remember { FaceClassifier(context) }
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }

    // --- [주소록 연동 로직 시작] ---

    // 1. 주소록 선택 후 돌아왔을 때 처리하는 런처
    val contactPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val contactUri = result.data?.data
            if (contactUri != null) {
                // 선택한 연락처에서 이름과 전화번호 추출
                val (name, phone) = getContactInfo(context, contactUri)
                inputName = name
                inputPhone = phone
                Toast.makeText(context, "연락처를 불러왔습니다!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // 2. 권한 요청 런처
    val requestContactPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            val intent = Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI)
            contactPickerLauncher.launch(intent)
        } else {
            Toast.makeText(context, "주소록 권한이 필요합니다.", Toast.LENGTH_SHORT).show()
        }
    }

    // 주소록 열기 함수
    fun openContactPicker() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
            val intent = Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI)
            contactPickerLauncher.launch(intent)
        } else {
            requestContactPermissionLauncher.launch(Manifest.permission.READ_CONTACTS)
        }
    }
    // --- [주소록 연동 로직 끝] ---

    LaunchedEffect(lensFacing, previewView, registeredPeople) {
        val pView = previewView ?: return@LaunchedEffect
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            cameraProvider.unbindAll()

            val preview = Preview.Builder().build()
            preview.setSurfaceProvider(pView.surfaceProvider)

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
                            currentFrameBitmap = rotatedFrame

                            for (face in faces) {
                                val rect = face.boundingBox
                                val safeX = rect.left.coerceAtLeast(0)
                                val safeY = rect.top.coerceAtLeast(0)
                                val safeWidth = rect.width().coerceAtMost(rotatedFrame.width - safeX)
                                val safeHeight = rect.height().coerceAtMost(rotatedFrame.height - safeY)

                                if (safeWidth > 0 && safeHeight > 0) {
                                    val croppedFace = Bitmap.createBitmap(rotatedFrame, safeX, safeY, safeWidth, safeHeight)
                                    val currentEmbedding = classifier.getFaceEmbedding(croppedFace)

                                    var bestPerson: Person? = null
                                    var globalMinDistance = Float.MAX_VALUE

                                    for (person in registeredPeople) {
                                        var localMinDistance = Float.MAX_VALUE
                                        for (vector in person.embeddings) {
                                            val distance = classifier.calculateDistance(currentEmbedding, vector)
                                            if (distance < localMinDistance) {
                                                localMinDistance = distance
                                            }
                                        }
                                        if (localMinDistance < globalMinDistance) {
                                            globalMinDistance = localMinDistance
                                            bestPerson = person
                                        }
                                    }

                                    var bestMatchName = "Unknown"
                                    var bestMatchInfo = ""
                                    val isRecognized = (globalMinDistance < 0.85f && bestPerson != null)

                                    if (isRecognized) {
                                        bestMatchName = bestPerson!!.name
                                        bestMatchInfo = bestPerson.info
                                    }
                                    results.add(FaceResult(rect, bestMatchName, bestMatchInfo, globalMinDistance))
                                }
                            }
                            faceResults = results
                        }
                        .addOnCompleteListener { imageProxy.close() }
                } else {
                    imageProxy.close()
                }
            }
            val cameraSelector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
            try {
                cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, preview, imageAnalysis)
            } catch (e: Exception) { e.printStackTrace() }
        }, ContextCompat.getMainExecutor(context))
    }

    BoxWithConstraints(
        modifier = Modifier.fillMaxSize().background(Color.Black)
    ) {
        val maxWidthPx = with(density) { maxWidth.toPx() }
        val maxHeightPx = with(density) { maxHeight.toPx() }

        Box(
            modifier = Modifier.fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures { tapOffset ->
                        if (isRegistrationMode && currentFrameBitmap != null) {
                            val scaleX = maxWidthPx / 480f
                            val scaleY = maxHeightPx / 640f
                            val isFront = (lensFacing == CameraSelector.LENS_FACING_FRONT)

                            for (result in faceResults) {
                                if (result.name == "Unknown" && !isRegistrationMode) continue

                                val rect = result.rect
                                val left = if (isFront) maxWidthPx - (rect.right * scaleX) else rect.left * scaleX
                                val right = if (isFront) maxWidthPx - (rect.left * scaleX) else rect.right * scaleX
                                val top = rect.top * scaleY
                                val bottom = rect.bottom * scaleY

                                if (tapOffset.x >= left && tapOffset.x <= right &&
                                    tapOffset.y >= top && tapOffset.y <= bottom) {

                                    try {
                                        val safeX = rect.left.coerceAtLeast(0)
                                        val safeY = rect.top.coerceAtLeast(0)
                                        val safeW = rect.width().coerceAtMost(currentFrameBitmap!!.width - safeX)
                                        val safeH = rect.height().coerceAtMost(currentFrameBitmap!!.height - safeY)

                                        if (safeW > 0 && safeH > 0) {
                                            selectedFaceBitmap = Bitmap.createBitmap(currentFrameBitmap!!, safeX, safeY, safeW, safeH)

                                            val existingPerson = registeredPeople.find { it.name == result.name && result.name != "Unknown" }

                                            if (existingPerson != null) {
                                                isEditingMode = true
                                                editingPersonId = existingPerson.id
                                                inputName = existingPerson.name
                                                inputPhone = existingPerson.phoneNumber ?: "" // 전화번호 불러오기
                                                inputInfo = existingPerson.info
                                            } else {
                                                isEditingMode = false
                                                inputName = ""
                                                inputPhone = ""
                                                inputInfo = ""
                                            }
                                            showInputDialog = true
                                        }
                                    } catch (e: Exception) { e.printStackTrace() }
                                    break
                                }
                            }
                        }
                    }
                }
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    PreviewView(ctx).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }.also { previewView = it }
                }
            )

            Canvas(modifier = Modifier.fillMaxSize()) {
                val scaleX = size.width / 480f
                val scaleY = size.height / 640f
                val isFront = (lensFacing == CameraSelector.LENS_FACING_FRONT)

                for (result in faceResults) {
                    if (!isRegistrationMode && result.name == "Unknown") continue

                    val rect = result.rect
                    val left = if (isFront) size.width - (rect.right * scaleX) else rect.left * scaleX
                    val right = if (isFront) size.width - (rect.left * scaleX) else rect.right * scaleX
                    val top = rect.top * scaleY
                    val bottom = rect.bottom * scaleY

                    drawRoundRect(
                        color = if (result.name == "Unknown") Color(0xFFFF5252) else Color(0xFF00E676),
                        topLeft = Offset(left, top),
                        size = Size(right - left, bottom - top),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(20f, 20f),
                        style = Stroke(width = 8f)
                    )
                }
            }

            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val scaleX = maxWidth.value / 480f
                val scaleY = maxHeight.value / 640f
                val isFront = (lensFacing == CameraSelector.LENS_FACING_FRONT)

                for (result in faceResults) {
                    if (result.name != "Unknown") {
                        val rect = result.rect
                        val rightSideX = if (isFront) 480f - rect.left else rect.right.toFloat()
                        val topSideY = rect.top.toFloat()

                        val offsetX = (rightSideX * scaleX).dp
                        val offsetY = (topSideY * scaleY).dp

                        Column(
                            modifier = Modifier
                                .offset { IntOffset(offsetX.roundToPx() + 20, offsetY.roundToPx()) }
                                .widthIn(max = 200.dp)
                                .heightIn(max = 200.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            Text(result.name, color = Color.Green, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                            Text(result.info, color = Color.White, fontSize = 14.sp)
                        }
                    }
                }
            }

            IconButton(
                onClick = { lensFacing = if (lensFacing == CameraSelector.LENS_FACING_FRONT) CameraSelector.LENS_FACING_BACK else CameraSelector.LENS_FACING_FRONT },
                modifier = Modifier.align(Alignment.TopEnd).padding(16.dp).size(50.dp).background(Color.Black.copy(alpha = 0.5f), CircleShape)
            ) { Icon(Icons.Filled.Refresh, "Switch Camera", tint = Color.White) }

            FloatingActionButton(
                onClick = { isRegistrationMode = !isRegistrationMode },
                containerColor = if (isRegistrationMode) Color.Red else Color(0xFF2196F3),
                contentColor = Color.White,
                modifier = Modifier.align(Alignment.BottomEnd).padding(30.dp)
            ) { if (isRegistrationMode) Icon(Icons.Filled.Close, "닫기") else Icon(Icons.Filled.Add, "등록 모드") }
        }
    }

    // [입력 팝업창]
    if (showInputDialog && selectedFaceBitmap != null) {
        Dialog(
            onDismissRequest = { showInputDialog = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(0.9f).wrapContentHeight().clip(RoundedCornerShape(24.dp)),
                color = Color.White
            ) {
                Column(
                    modifier = Modifier.padding(24.dp).verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(if(isEditingMode) "정보 수정" else "신규 인맥 등록", fontSize = 20.sp, fontWeight = FontWeight.Bold)

                    Image(
                        bitmap = selectedFaceBitmap!!.asImageBitmap(),
                        contentDescription = "Face",
                        modifier = Modifier.size(120.dp).clip(RoundedCornerShape(12.dp)).border(2.dp, Color.Gray, RoundedCornerShape(12.dp))
                    )

                    // [New] 주소록 불러오기 버튼
                    OutlinedButton(
                        onClick = { openContactPicker() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.AccountCircle, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("연락처에서 가져오기")
                    }

                    OutlinedTextField(
                        value = inputName,
                        onValueChange = { inputName = it },
                        label = { Text("이름") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    // [New] 전화번호 입력 필드
                    OutlinedTextField(
                        value = inputPhone,
                        onValueChange = { inputPhone = it },
                        label = { Text("전화번호") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = inputInfo,
                        onValueChange = { inputInfo = it },
                        label = { Text("메모 / 직함") },
                        modifier = Modifier.fillMaxWidth().height(100.dp),
                        singleLine = false
                    )

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        OutlinedButton(onClick = { showInputDialog = false }) { Text("취소") }

                        Button(
                            onClick = {
                                if (inputName.isBlank()) {
                                    Toast.makeText(context, "이름을 입력하세요", Toast.LENGTH_SHORT).show()
                                    return@Button
                                }
                                val embedding = classifier.getFaceEmbedding(selectedFaceBitmap!!)

                                if (isEditingMode) {
                                    val originalPerson = registeredPeople.find { it.id == editingPersonId }
                                    if (originalPerson != null) {
                                        val newEmbeddings = originalPerson.embeddings.toMutableList()
                                        if (newEmbeddings.size < 5) newEmbeddings.add(embedding)

                                        val updatedPerson = originalPerson.copy(
                                            name = inputName,
                                            info = inputInfo,
                                            phoneNumber = inputPhone, // 전화번호 업데이트
                                            embeddings = newEmbeddings
                                        )
                                        viewModel.updatePerson(updatedPerson)
                                        Toast.makeText(context, "정보가 수정되었습니다.", Toast.LENGTH_SHORT).show()
                                    }
                                } else {
                                    viewModel.addPerson(
                                        name = inputName,
                                        info = inputInfo,
                                        phone = inputPhone, // 전화번호 저장
                                        embeddings = listOf(embedding)
                                    )
                                    Toast.makeText(context, "새로운 인맥이 등록되었습니다!", Toast.LENGTH_SHORT).show()
                                }

                                showInputDialog = false
                                isRegistrationMode = false
                            }
                        ) { Text("저장") }
                    }
                }
            }
        }
    }
}

// [주소록 파싱 도우미 함수]
@SuppressLint("Range")
fun getContactInfo(context: Context, contactUri: Uri): Pair<String, String> {
    var name = ""
    var phone = ""

    try {
        val cursor = context.contentResolver.query(contactUri, null, null, null, null)
        if (cursor != null && cursor.moveToFirst()) {
            // 1. 이름 가져오기
            name = cursor.getString(cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)) ?: ""

            // 2. 전화번호 가져오기
            phone = cursor.getString(cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)) ?: ""

            cursor.close()
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
    return Pair(name, phone)
}

fun rotateBitmap(bitmap: Bitmap, degrees: Float): Bitmap {
    val matrix = Matrix()
    matrix.postRotate(degrees)
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
}