package com.kroryan.astrovia.ui

import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kroryan.astrovia.BuildConfig
import com.kroryan.astrovia.R
import com.kroryan.astrovia.ui.theme.SkyBlack
import com.kroryan.astrovia.ui.theme.TextPrimary
import com.kroryan.astrovia.ui.theme.TextSecondary

/** Persists whether the user has already accepted the first-launch notice. */
object OnboardingPrefs {
    private const val PREFS = "astrovia_onboarding"
    private const val K_NOTICE = "notice_accepted_v1"

    fun isAccepted(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(K_NOTICE, false)

    fun setAccepted(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(K_NOTICE, true).apply()
    }
}

@Composable
private fun NoticeSection(title: String, body: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = TextPrimary)
        Spacer(Modifier.height(2.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
    }
}

/** Notices shared by the first-launch screen and the About dialog. */
@Composable
private fun NoticeBody() {
    NoticeSection(stringResource(R.string.notice_origin_title), stringResource(R.string.notice_origin_body))
    NoticeSection(stringResource(R.string.notice_unofficial_title), stringResource(R.string.notice_unofficial_body))
    NoticeSection(stringResource(R.string.notice_ai_title), stringResource(R.string.notice_ai_body))
    NoticeSection(stringResource(R.string.notice_license_title), stringResource(R.string.notice_license_body))
    NoticeSection(stringResource(R.string.notice_data_title), stringResource(R.string.notice_data_body))
}

@Composable
private fun Header() {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Image(
            painter = painterResource(R.drawable.astrovia_logo),
            contentDescription = null,
            modifier = Modifier.size(112.dp),
        )
        Text(
            stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = TextPrimary,
        )
        Text(
            stringResource(R.string.welcome_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
            textAlign = TextAlign.Center,
        )
    }
}

/** Full-screen notice shown the first time the app is opened. */
@Composable
fun WelcomeScreen(onAccept: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    val sourceUrl = stringResource(R.string.source_url)
    Surface(color = SkyBlack, modifier = Modifier.fillMaxSize()) {
        Column(
            Modifier.safeDrawingPadding().padding(horizontal = 20.dp, vertical = 12.dp),
        ) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                Header()
                Spacer(Modifier.height(12.dp))
                NoticeBody()
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { uriHandler.openUri(sourceUrl) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.about_open_source))
            }
            Spacer(Modifier.height(8.dp))
            Button(onClick = onAccept, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.welcome_accept))
            }
        }
    }
}

/** "About" dialog reachable from the navigation drawer. */
@Composable
fun AboutDialog(onDismiss: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    val sourceUrl = stringResource(R.string.source_url)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.about_menu_item)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                Header()
                Text(
                    stringResource(R.string.about_version, BuildConfig.VERSION_NAME),
                    style = MaterialTheme.typography.labelMedium,
                    color = TextSecondary,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(8.dp))
                NoticeBody()
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.about_close)) } },
        dismissButton = { TextButton(onClick = { uriHandler.openUri(sourceUrl) }) { Text(stringResource(R.string.about_open_source)) } },
    )
}
