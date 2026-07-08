package com.example.exampleapp

import android.Manifest
import android.content.pm.PackageManager
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
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.*

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

    var countdown by remember { mutableStateOf<Int?>(null) } // null = not counting down
    val imageCapture = remember { ImageCapture.Builder().build() }

    Box(modifier = Modifier.fillMaxSize()) {
        // Camera preview fills the screen
        AndroidView(
            factory = { ctx ->
                val previewView = PreviewView(ctx)
                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)

                cameraProviderFuture.addListener({
                    val cameraProvider = cameraProviderFuture.get()

                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
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

        // Countdown overlay
        countdown?.let { count ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.4f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (count > 0) count.toString() else "📸",
                    color = Color.White,
                    fontSize = 96.sp
                )
            }
        }

        // Trigger countdown + capture
        LaunchedEffect(countdown) {
            if (countdown != null && countdown!! > 0) {
                delay(1000)
                countdown = countdown!! - 1
            } else if (countdown == 0) {
                delay(300) // brief pause on the emoji before capture
                capturePhoto(context, imageCapture, onPhotoTaken)
                countdown = null
            }
        }

        // Round capture button
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

@Composable
fun ReviewScreen(bitmap: Bitmap, onRetake: () -> Unit) {
    var email by remember { mutableStateOf("") }

    Box(modifier = Modifier.fillMaxSize()) {
        // Photo fills the entire screen
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "Captured photo",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )

        // Logo overlay
        Image(
            painter = painterResource(id = R.drawable.logo),
            contentDescription = "Logo",
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 140.dp) // sits above the email/button controls
                .fillMaxWidth(0.25f)
        )

        // Controls floating over the bottom of the photo
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.5f))
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                label = { Text("Email address", color = Color.White) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White
                ),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                Button(onClick = onRetake) {
                    Text("Retake")
                }

                Button(onClick = {
                    println("Would send photo to: $email")
                }) {
                    Text("Send")
                }
            }
        }
    }
}