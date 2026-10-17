@file:Suppress("EmptyCatchBlock", "TooGenericExceptionThrown", "VariableNaming", "ImplicitDefaultLocale", "WildcardImport", "MagicNumber", "MaxLineLength", "ComplexCondition", "CyclomaticComplexMethod", "LongMethod", "ReturnCount", "LongParameterList", "SwallowedException", "TooGenericExceptionCaught", "NestedBlockDepth", "FunctionNaming", "LoopWithTooManyJumpStatements", "TooManyFunctions")
package com.kroryan.astrovia

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.kroryan.astrovia.ui.OnboardingPrefs
import com.kroryan.astrovia.ui.SkyScreen
import com.kroryan.astrovia.ui.WelcomeScreen
import com.kroryan.astrovia.ui.theme.AstroviaTheme

class MainActivity : ComponentActivity() {
    private val vmForLinks: SkyViewModel by viewModels()

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        handleIntent(vmForLinks, intent)
    }

    /** astrovia:// links, "Open with" and "Share" of FITS / VOTable / CSV files. */
    private fun handleIntent(vm: SkyViewModel, intent: android.content.Intent?) {
        intent ?: return
        val data = intent.data
        if (data != null) {
            if (data.scheme == "astrovia") vm.openViewLink(data) else vm.openFile(data)
        } else if (intent.action == android.content.Intent.ACTION_SEND) {
            @Suppress("DEPRECATION")
            (intent.getParcelableExtra<android.net.Uri>(android.content.Intent.EXTRA_STREAM))?.let { vm.openFile(it) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val vm: SkyViewModel by viewModels()
        handleIntent(vm, intent)
        setContent {
            AstroviaTheme {
                var accepted by remember { mutableStateOf(OnboardingPrefs.isAccepted(this)) }
                if (accepted) {
                    SkyScreen(vm)
                } else {
                    WelcomeScreen(onAccept = {
                        OnboardingPrefs.setAccepted(this)
                        accepted = true
                    })
                }
            }
        }
    }
}
