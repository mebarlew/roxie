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
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

private val Ink = Color(0xFF34285C)

/** Setup: pick a buddy and colour, your name, the permissions it needs, and the switch that wakes it up. */
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
    var buddy by remember { mutableStateOf(prefs.buddy) }
    var color by remember { mutableStateOf(prefs.color) }
    val accent = Color(CANDY.getValue(color))
    val title = buddy.title

    MaterialTheme(colorScheme = lightColorScheme(primary = accent, onPrimary = Color.White)) {
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
            BuddyPreview(buddy, accent)
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
                label = { Text("What should $title call you?") },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth(),
            )
            BuddyPicker(buddy, accent) {
                buddy = it
                prefs.buddy = it
            }
            ColorPicker(color) {
                color = it
                prefs.color = it
            }
            Step("Float over your apps", "So $title can sit on top of anything", overlayOk, onOverlay)
            Step("Hear you", "Tap, talk, $title listens", micOk) { onPermission(Manifest.permission.RECORD_AUDIO) }
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
                Text("Wake $title up", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
            TextButton(onClick = onSleep) { Text("Put $title to sleep", color = Ink.copy(alpha = 0.6f)) }
        }
    }
}

/** The chosen buddy playing its idle animation in a disc with the colour ring, like the real bubble. */
@Composable
private fun BuddyPreview(buddy: Buddy, ring: Color) {
    val context = LocalContext.current
    val steps = remember { BuddyArt.anim(context, Anim.IDLE) }
    var step by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(STEP_MS)
            step = (step + 1) % steps.size
        }
    }
    val frame = remember(buddy, step) { BuddyArt.frame(context, buddy, steps[step].frame).asImageBitmap() }
    Box(
        Modifier.size(200.dp).clip(CircleShape).background(Color.White).border(8.dp, ring, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            frame,
            buddy.title,
            Modifier.size(160.dp).offset(y = (steps[step].dy * 2.5f).dp),
            filterQuality = FilterQuality.None,
        )
    }
}

@Composable
private fun BuddyPicker(selected: Buddy, accent: Color, onPick: (Buddy) -> Unit) {
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Pick your buddy", fontWeight = FontWeight.Bold, color = Ink)
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Buddy.entries.forEach { b ->
                val icon = remember(b) { BuddyArt.frame(context, b, IDLE_FRAME).asImageBitmap() }
                val shape = RoundedCornerShape(16.dp)
                Column(
                    Modifier
                        .clip(shape)
                        .background(Color.White)
                        .border(3.dp, if (b == selected) accent else Color.White, shape)
                        .clickable { onPick(b) }
                        .padding(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Image(icon, b.title, Modifier.size(56.dp), filterQuality = FilterQuality.None)
                    Text(b.title, fontSize = 12.sp, color = Ink)
                }
            }
        }
    }
}

@Composable
private fun ColorPicker(selected: String, onPick: (String) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Bubble colour", fontWeight = FontWeight.Bold, color = Ink)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CANDY.forEach { (name, argb) ->
                Box(
                    Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(Color(argb))
                        .border(3.dp, if (name == selected) Ink else Color.White, CircleShape)
                        .clickable { onPick(name) },
                )
            }
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
