package com.starlane.share.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.starlane.share.Prefs
import com.starlane.share.R
import com.starlane.share.engine.ShareManager
import com.starlane.share.ui.GlyphIcon
import com.starlane.share.ui.Palette
import com.starlane.share.ui.PrimaryButton
import com.starlane.share.ui.deviceGlyph

/** Profile editor used for first-run onboarding and later from Settings. */
@Composable
fun ProfileEditor(initialEdit: Boolean, onDone: () -> Unit) {
    val prefs = ShareManager.prefs
    var name by remember { mutableStateOf(prefs.username) }
    var nick by remember { mutableStateOf(prefs.nickname) }
    var icon by remember { mutableIntStateOf(prefs.icon) }
    val valid = name.trim().length >= 2

    Column(Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = name, onValueChange = { name = it.take(Prefs.MAX_NAME) }, singleLine = true,
            label = { Text(stringResource(R.string.onb_username)) },
            supportingText = { Text(stringResource(R.string.onb_username_hint)) },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = nick, onValueChange = { nick = it.take(Prefs.MAX_NAME) }, singleLine = true,
            label = { Text(stringResource(R.string.onb_nickname)) }, modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.onb_icon), style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            for (i in 0 until Prefs.ICONS) {
                val sel = i == icon
                Box(
                    Modifier.size(46.dp).clip(CircleShape)
                        .background(if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                        .border(if (sel) 2.dp else 0.dp, MaterialTheme.colorScheme.secondary, CircleShape)
                        .clickable(role = Role.RadioButton) { icon = i },
                    contentAlignment = Alignment.Center,
                ) { GlyphIcon(deviceGlyph(i), if (sel) Color.White else MaterialTheme.colorScheme.onSurfaceVariant, 22.dp) }
            }
        }
        Spacer(Modifier.height(20.dp))
        PrimaryButton(
            stringResource(if (initialEdit) R.string.onb_start else R.string.action_save),
            {
                prefs.username = name; prefs.nickname = nick; prefs.icon = icon
                ShareManager.profileChanged()
                onDone()
            },
            Modifier.fillMaxWidth(), enabled = valid,
        )
    }
}

@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding().navigationBarsPadding().imePadding()
            .verticalScroll(rememberScrollState()).padding(24.dp),
    ) {
        Spacer(Modifier.height(24.dp))
        Box(Modifier.size(72.dp).clip(CircleShape).background(Brush.linearGradient(listOf(Palette.Night, Palette.OrbitDeep))), contentAlignment = Alignment.Center) {
            GlyphIcon(com.starlane.share.ui.Glyph.STAR, Color.White, 34.dp)
        }
        Spacer(Modifier.height(20.dp))
        Text(stringResource(R.string.onb_title), style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(6.dp))
        Text(stringResource(R.string.onb_subtitle), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(24.dp))
        ProfileEditor(initialEdit = true, onDone = onDone)
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.onb_privacy), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
