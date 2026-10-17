package dev.mebarlew.roxie

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Ink = Color(0xFF34285C)
private val Blueberry = Color(0xFF6EA0F0)

/** Setup: her name for you, the permissions she needs, and the switch that wakes her up. */
class MainActivity : ComponentActivity() {
    private var refresh by mutableIntStateOf(0)
    private val requestPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { refresh++ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val prefs = Prefs(this)
        setContent {
            SetupScreen(
                refresh = refresh,
                prefs = prefs,
                onOverlay = {
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                },
                onPermission = { requestPermission.launch(it) },
                onWake = {
                    startForegroundService(Intent(this, BubbleService::class.java))
                    moveTaskToBack(true)
                },
                onSleep = { stopService(Intent(this, BubbleService::class.java)) },
            )
        }
    }

    override fun onResume() {
        super.onResume()
        refresh++
    }
}

@Composable
private fun SetupScreen(
    refresh: Int,
    prefs: Prefs,
    onOverlay: () -> Unit,
    onPermission: (String) -> Unit,
    onWake: () -> Unit,
    onSleep: () -> Unit,
) {
    val context = LocalContext.current
    var name by remember { mutableStateOf(prefs.name) }
    val overlayOk = remember(refresh) { Settings.canDrawOverlays(context) }
    val micOk = remember(refresh) { granted(context, Manifest.permission.RECORD_AUDIO) }
    val notifyOk = remember(refresh) {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || granted(context, Manifest.permission.POST_NOTIFICATIONS)
    }
    val roxie = remember { Sprite.bitmap(Pose.HAPPY).asImageBitmap() }

    MaterialTheme(colorScheme = lightColorScheme(primary = Blueberry, onPrimary = Color.White)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(Color(0xFFFFE4F0), Color(0xFFD6E4FF))))
                .systemBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Image(roxie, "Roxie", Modifier.size(200.dp), filterQuality = FilterQuality.None)
            Text("Rubber Duck Assistant", fontSize = 30.sp, fontWeight = FontWeight.Black, color = Ink)
            Text(
                "Your tiny helper that floats over every app",
                color = Ink.copy(alpha = 0.7f),
                textAlign = TextAlign.Center,
            )
            OutlinedTextField(
                value = name,
                onValueChange = {
                    name = it
                    prefs.name = it.trim()
                },
                label = { Text("What should she call you?") },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth(),
            )
            Step("Float over your apps", "So she can sit on top of anything", overlayOk, onOverlay)
            Step("Hear you", "Tap her, talk, she listens", micOk) { onPermission(Manifest.permission.RECORD_AUDIO) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Step("Stay awake", "Android needs a small notification for that", notifyOk) {
                    onPermission(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
            Button(
                onClick = onWake,
                enabled = overlayOk && micOk && name.isNotBlank(),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) {
                Text("Wake Roxie up", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
            TextButton(onClick = onSleep) { Text("Put her to sleep", color = Ink.copy(alpha = 0.6f)) }
        }
    }
}

@Composable
private fun Step(title: String, subtitle: String, done: Boolean, onClick: () -> Unit) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Bold, color = Ink)
                Text(subtitle, fontSize = 13.sp, color = Ink.copy(alpha = 0.6f))
            }
            if (done) {
                Text("✓", fontSize = 22.sp, color = Color(0xFF3CB371), fontWeight = FontWeight.Bold)
            } else {
                Button(onClick = onClick, shape = RoundedCornerShape(14.dp)) { Text("Allow") }
            }
        }
    }
}

private fun granted(context: Context, permission: String) =
    context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
