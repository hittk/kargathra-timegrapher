package com.kargathra.timegrapher

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.kargathra.timegrapher.ui.components.KargathraLogo
import com.kargathra.timegrapher.ui.screens.MainScreen
import com.kargathra.timegrapher.ui.theme.KargathraColors
import com.kargathra.timegrapher.ui.theme.KargathraTheme

/**
 * Permission flow states.
 *
 *   NOT_REQUESTED     — First launch; show "Grant" button.
 *   DENIED_RATIONALE  — User denied once but the system will still prompt again.
 *   DENIED_PERMANENT  — User denied with "Don't ask again". System dialog
 *                       will no longer appear → must send them to App Settings.
 *   GRANTED           — Show the app.
 */
private enum class PermState { NOT_REQUESTED, DENIED_RATIONALE, DENIED_PERMANENT, GRANTED }

class MainActivity : ComponentActivity() {

    private var permState by mutableStateOf(PermState.NOT_REQUESTED)

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            permState = when {
                granted -> PermState.GRANTED
                shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) ->
                    PermState.DENIED_RATIONALE
                else -> PermState.DENIED_PERMANENT
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        refreshPermState()

        setContent {
            KargathraTheme {
                when (permState) {
                    PermState.GRANTED -> MainScreen()
                    else -> PermissionScreen(
                        state      = permState,
                        onRequest  = { requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                        onSettings = { openAppSettings() }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Re-check on return from Settings (user may have granted)
        refreshPermState()
    }

    private fun refreshPermState() {
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (granted) {
            permState = PermState.GRANTED
        } else if (permState != PermState.DENIED_PERMANENT) {
            permState = PermState.NOT_REQUESTED
        }
    }

    private fun openAppSettings() {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", packageName, null)
        ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        startActivity(intent)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Branded permission screen
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun PermissionScreen(
    state: PermState,
    onRequest: () -> Unit,
    onSettings: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(KargathraColors.NavyDeep),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp)
        ) {
            // Large brand mark
            KargathraLogo(size = 96.dp)

            Spacer(Modifier.height(24.dp))

            Text(
                text          = "KARGATHRA & CO.",
                color         = KargathraColors.GoldPrimary,
                fontSize      = 22.sp,
                fontFamily    = FontFamily.Serif,
                fontWeight    = FontWeight.Normal,
                letterSpacing = 3.sp
            )

            Text(
                text          = "TIMEGRAPHER",
                color         = KargathraColors.GoldMuted,
                fontSize      = 10.sp,
                letterSpacing = 5.sp
            )

            Spacer(Modifier.height(48.dp))

            val (message, buttonLabel, action) = when (state) {
                PermState.NOT_REQUESTED    -> Triple(
                    "Microphone access is required to measure watch timing via acoustic analysis of the escapement.",
                    "GRANT MICROPHONE ACCESS",
                    onRequest
                )
                PermState.DENIED_RATIONALE -> Triple(
                    "Microphone access was denied. The instrument cannot measure without it — please grant access to continue.",
                    "TRY AGAIN",
                    onRequest
                )
                PermState.DENIED_PERMANENT -> Triple(
                    "Microphone access has been permanently denied. Please enable it manually in App Settings → Permissions.",
                    "OPEN APP SETTINGS",
                    onSettings
                )
                PermState.GRANTED -> Triple("", "", {})
            }

            Text(
                text       = message,
                color      = KargathraColors.CreamMuted,
                fontSize   = 14.sp,
                lineHeight = 20.sp,
                textAlign  = TextAlign.Center,
                modifier   = Modifier.widthIn(max = 320.dp)
            )

            Spacer(Modifier.height(32.dp))

            PrimaryButton(label = buttonLabel, onClick = action)
        }
    }
}

@Composable
private fun PrimaryButton(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(3.dp))
            .background(KargathraColors.GoldPrimary)
            .border(1.dp, KargathraColors.GoldBright, RoundedCornerShape(3.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 28.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text          = label,
            color         = KargathraColors.NavyDeep,
            fontSize      = 12.sp,
            fontWeight    = FontWeight.Bold,
            letterSpacing = 2.sp
        )
    }
}
