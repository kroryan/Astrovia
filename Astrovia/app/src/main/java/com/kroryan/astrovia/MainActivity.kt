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
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val vm: SkyViewModel by viewModels()
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
