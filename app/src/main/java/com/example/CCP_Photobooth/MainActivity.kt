package com.example.CCP_Photobooth

import android.Manifest
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import android.util.Base64
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import android.util.Log
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import android.graphics.Canvas
import android.widget.Toast
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.BorderStroke
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.ui.draw.rotate
import android.graphics.Paint
import android.graphics.Typeface

val httpClient = OkHttpClient() // reused across calls, don't recreate per request

// Shared layout constants — change these once, both the preview and the sent photo follow
object LogoLayout {
    const val LOGO_WIDTH_FRACTION = 0.6f
    const val BOTTOM_PADDING_DP = 140
    const val ADDRESS_TEXT_SIZE_SP = 20
    const val ADDRESS_SPACER_DP = 4
    const val ADDRESS_TEXT = "Kraftværksvej 24, 2300 København"
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                PhotoboothApp()
            }
        }
    }
}

// Simple sealed class to track which screen we're on
sealed class Screen {
    object Camera : Screen()
    data class Review(val bitmap: Bitmap) : Screen()
}

@Composable
fun PhotoboothApp() {
    var screen by remember { mutableStateOf<Screen>(Screen.Camera) }

    when (val current = screen) {
        is Screen.Camera -> CameraScreen(
            onPhotoTaken = { bitmap -> screen = Screen.Review(bitmap) }
        )
        is Screen.Review -> ReviewScreen(
            bitmap = current.bitmap,
            onRetake = { screen = Screen.Camera }
        )
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun CameraScreen(onPhotoTaken: (Bitmap) -> Unit) {
    val cameraPermissionState = rememberPermissionState(Manifest.permission.CAMERA)

    LaunchedEffect(Unit) {
        if (!cameraPermissionState.status.isGranted) {
            cameraPermissionState.launchPermissionRequest()
        }
    }

    if (cameraPermissionState.status.isGranted) {
        CameraContent(onPhotoTaken = onPhotoTaken)
    } else {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text("Camera permission is required to use the photobooth.")
        }
    }
}

fun rotateBitmap(source: Bitmap, degrees: Float): Bitmap {
    val matrix = android.graphics.Matrix()
    matrix.postRotate(degrees)
    return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
}

@Composable
fun CameraContent(onPhotoTaken: (Bitmap) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var countdown by remember { mutableStateOf<Int?>(null) }
    val imageCapture = remember { ImageCapture.Builder().build() }

    val flashAlpha = remember { Animatable(0f) }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                val previewView = PreviewView(ctx)
                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)

                cameraProviderFuture.addListener({
                    val cameraProvider = cameraProviderFuture.get()

                    val preview = Preview.Builder().build().also {
                        it.surfaceProvider = previewView.surfaceProvider
                    }

                    val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                    try {
                        cameraProvider.unbindAll()
                        cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            cameraSelector,
                            preview,
                            imageCapture
                        )
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }, ContextCompat.getMainExecutor(ctx))

                previewView
            },
            modifier = Modifier.fillMaxSize()
        )

        countdown?.let { count ->
            if (count > 0) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.4f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = count.toString(),
                        color = Color.White,
                        fontSize = 96.sp
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.White.copy(alpha = flashAlpha.value))
        )

        LaunchedEffect(countdown) {
            if (countdown != null && countdown!! > 0) {
                delay(1000.milliseconds)
                countdown = countdown!! - 1
            } else if (countdown == 0) {
                delay(300.milliseconds)
                flashAlpha.snapTo(1f)
                capturePhoto(context, imageCapture, onPhotoTaken)
                flashAlpha.animateTo(
                    targetValue = 0f,
                    animationSpec = tween(durationMillis = 400)
                )
                countdown = null
            }
        }

        // The capture button — this is what got dropped last time
        if (countdown == null) {
            Button(
                onClick = {
                    if (countdown == null) countdown = 3
                },
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(containerColor = Color.White),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 32.dp)
                    .size(80.dp)
            ) {}
        }

        }
}

fun capturePhoto(
    context: android.content.Context,
    imageCapture: ImageCapture,
    onPhotoTaken: (Bitmap) -> Unit
) {
    imageCapture.takePicture(
        ContextCompat.getMainExecutor(context),
        object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val bitmap = imageProxyToBitmap(image)
                image.close()
                onPhotoTaken(bitmap)

                val rotatedBitmap = rotateBitmap(bitmap, 90f)
                onPhotoTaken(rotatedBitmap)
            }

            override fun onError(exception: ImageCaptureException) {
                exception.printStackTrace()
            }
        }
    )
}

fun imageProxyToBitmap(image: ImageProxy): Bitmap {
    val buffer = image.planes[0].buffer
    val bytes = ByteArray(buffer.remaining())
    buffer.get(bytes)
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
}
fun addLogoToBitmap(context: android.content.Context, source: Bitmap): Bitmap {
    val result = source.copy(Bitmap.Config.ARGB_8888, true)
    val canvas = Canvas(result)

    val logoBitmap = BitmapFactory.decodeResource(context.resources, R.drawable.logo)

    val logoWidth = result.width * LogoLayout.LOGO_WIDTH_FRACTION
    val scale = logoWidth / logoBitmap.width
    val logoHeight = logoBitmap.height * scale

    val scaledLogo = Bitmap.createScaledBitmap(logoBitmap, logoWidth.toInt(), logoHeight.toInt(), true)

    val density = context.resources.displayMetrics.density
    val bottomPaddingPx = LogoLayout.BOTTOM_PADDING_DP * density

    val logoLeft = (result.width - scaledLogo.width) / 2f
    val logoTop = result.height - bottomPaddingPx - scaledLogo.height

    canvas.drawBitmap(scaledLogo, logoLeft, logoTop, null)

    val paint = Paint().apply {
        color = android.graphics.Color.WHITE
        textSize = LogoLayout.ADDRESS_TEXT_SIZE_SP * density
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
    }

    val spacerPx = LogoLayout.ADDRESS_SPACER_DP * density
    val textBaselineY = logoTop + scaledLogo.height + spacerPx + paint.textSize

    canvas.drawText(LogoLayout.ADDRESS_TEXT, result.width / 2f, textBaselineY, paint)

    return result
}

@Composable
fun ReviewScreen(bitmap: Bitmap, onRetake: () -> Unit) {
    var email by remember { mutableStateOf("") }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val context = LocalContext.current
    var isSending by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) {
                keyboardController?.hide()
                focusManager.clearFocus()
            }) {

        // Photo fills the entire screen
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "Captured photo",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = LogoLayout.BOTTOM_PADDING_DP.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Image(
                painter = painterResource(id = R.drawable.logo),
                contentDescription = "Logo",
                modifier = Modifier.fillMaxWidth(LogoLayout.LOGO_WIDTH_FRACTION)
            )

            Spacer(modifier = Modifier.height(LogoLayout.ADDRESS_SPACER_DP.dp))

            Text(
                text = LogoLayout.ADDRESS_TEXT,
                color = Color.White,
                fontSize = LogoLayout.ADDRESS_TEXT_SIZE_SP.sp
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .imePadding()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                label = { Text("Email address", color = Color.White) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = Color.White,
                    unfocusedBorderColor = Color.White,
                    cursorColor = Color.White,
                    focusedLabelColor = Color.White,
                    unfocusedLabelColor = Color.White
                ),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(
                    onDone = {
                        keyboardController?.hide()
                        focusManager.clearFocus()
                    }
                ),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                OutlinedButton(
                    onClick = onRetake,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                    border = BorderStroke(1.dp, Color.White)
                ) {
                    Text("Retake")
                }

                Button(
                    onClick = {
                        keyboardController?.hide()
                        focusManager.clearFocus()
                        val testEmail = "madswint@protonmail.com"
                        Log.d("PhotoSend", "Send button clicked, target=$testEmail")
                        val bitmapWithLogo = addLogoToBitmap(context, bitmap)

                        isSending = true

                        sendPhotoToServer(context, bitmapWithLogo, testEmail) { success ->
                            isSending = false
                            statusMessage = if (success) "Sent!" else "Error sending..."
                            if (success) {
                                email = ""
                                keyboardController?.hide()
                                focusManager.clearFocus()
                                onRetake()
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White,
                        contentColor = Color.Black
                    )
                ) {
                    Text("Send")
                }
            }

            statusMessage?.let {
                Spacer(modifier = Modifier.height(8.dp))
                Text(it, color = Color.White)
            }
        }

        // Only overlay block — declared LAST so it renders on top
        if (isSending) {
            val infiniteTransition = rememberInfiniteTransition(label = "cog_rotation")
            val rotation by infiniteTransition.animateFloat(
                initialValue = 0f,
                targetValue = 360f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 1200, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart
                ),
                label = "cog_rotation_value"
            )

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.6f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "Sending...",
                    tint = Color.White,
                    modifier = Modifier
                        .size(64.dp)
                        .rotate(rotation)
                )
            }
        }
    } // closes outer Box
} // closes fun ReviewScreen





fun sendPhotoToServer(
    context: android.content.Context,
    bitmap: Bitmap,
    email: String,
    onResult: (success: Boolean) -> Unit
) {
    Thread {
        try {
            Log.d("PhotoSend", "Starting send process")
            val outputStream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, outputStream)
            val base64Image = Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
            Log.d("PhotoSend", "Encoded image, size=${base64Image.length} chars")
            val json = JSONObject().apply {
                put("email", email)
                put("image_base64", base64Image)
            }
            val body = json.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("http://127.0.0.1:5000/send-photo")
                .post(body)
                .build()
            Log.d("PhotoSend", "Sending request to ${request.url}")
            httpClient.newCall(request).execute().use { response ->
                val text = response.body?.string() ?: ""
                Log.d("PhotoSend", "Response code=${response.code}, body=$text")
                val success = response.isSuccessful

                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    if (success) {
                        Toast.makeText(context, "Sent!", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "Error sending...", Toast.LENGTH_SHORT).show()
                    }
                }

                onResult(success)
            }
        } catch (e: Exception) {
            Log.e("PhotoSend", "Send failed", e)
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                Toast.makeText(context, "Error sending...", Toast.LENGTH_SHORT).show()
            }
            onResult(false)
        }
    }.start()
}