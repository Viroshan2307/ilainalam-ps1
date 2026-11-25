package com.ilainalam

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.ilainalam.i18n.LocalStrings
import com.ilainalam.i18n.stringsFor
import com.ilainalam.ui.MainViewModel
import com.ilainalam.ui.screens.AboutScreen
import com.ilainalam.ui.screens.AdviceScreen
import com.ilainalam.ui.screens.BenchmarkScreen
import com.ilainalam.ui.screens.CameraScreen
import com.ilainalam.ui.screens.CaptureScreen
import com.ilainalam.ui.screens.HomeScreen
import com.ilainalam.ui.screens.LanguageScreen
import com.ilainalam.ui.screens.MissingAssetsScreen
import com.ilainalam.ui.screens.AdvisorScreen
import com.ilainalam.ui.screens.ResultScreen
import com.ilainalam.ui.screens.StatePicker
import com.ilainalam.ui.screens.SetupScreen
import com.ilainalam.ui.theme.IlaiNalamTheme

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val lang by vm.lang.collectAsStateWithLifecycle()
            IlaiNalamTheme {
                CompositionLocalProvider(LocalStrings provides stringsFor(lang)) {
                    if (vm.assets.missing.isNotEmpty()) MissingAssetsScreen(vm.assets.missing) else AppNav(vm)
                }
            }
        }
    }
}

@Composable
private fun AppNav(vm: MainViewModel) {
    val nav = rememberNavController()
    // decided once, so changing language doesn't rebuild the navigation graph
    val start = remember {
        when {
            !vm.languageChosen -> "language"
            !vm.llmFilesPresent() -> "setup"
            else -> "home"
        }
    }
    fun goHome() = nav.navigate("home") { popUpTo(nav.graph.id) { inclusive = true } }

    NavHost(navController = nav, startDestination = start) {
        composable("language") {
            LanguageScreen(vm) {
                if (vm.llmFilesPresent()) goHome()
                else nav.navigate("setup") { popUpTo("language") { inclusive = true } }
            }
        }
        composable("setup") {
            SetupScreen(vm,
                onBack = if (nav.previousBackStackEntry != null) ({ nav.popBackStack(); Unit }) else null,
                onDone = { goHome() })
        }
        composable("home") {
            HomeScreen(vm,
                onScan = { vm.cancelAddPhoto(); nav.navigate("capture") },
                onOpenHistory = { vm.openHistory(it); nav.navigate("result") },
                onSetup = { nav.navigate("setup") },
                onBenchmark = { nav.navigate("bench") },
                onAbout = { nav.navigate("about") },
                onAdvisor = { nav.navigate("advisor") },
                onState = { nav.navigate("state") })
        }
        composable("capture") {
            CaptureScreen(vm, onBack = { vm.cancelAddPhoto(); nav.popBackStack() },
                onPhoto = { nav.navigate("result") { popUpTo("capture") { inclusive = true } } },
                onLiveCamera = { nav.navigate("camera") })
        }
        composable("camera") {
            CameraScreen(vm, onBack = { nav.popBackStack() },
                onPhoto = { nav.navigate("result") { popUpTo("capture") { inclusive = true } } },
                onUsePhoneCamera = { nav.popBackStack() })
        }
        composable("result") {
            ResultScreen(vm,
                onBack = { nav.popBackStack() },
                onRetake = { vm.cancelAddPhoto(); nav.navigate("capture") { popUpTo("result") { inclusive = true } } },
                onAddPhoto = { vm.startAddPhoto(); nav.navigate("capture") { popUpTo("result") { inclusive = true } } },
                onAdvice = { nav.navigate("advice") })
        }
        composable("advice") { AdviceScreen(vm, onBack = { nav.popBackStack() }) }
        composable("advisor") { AdvisorScreen(vm, onBack = { nav.popBackStack() }) }
        composable("state") { StatePicker(vm, onDone = { nav.popBackStack() }) }
        composable("bench") { BenchmarkScreen(vm, onBack = { nav.popBackStack() }) }
        composable("about") { AboutScreen(onBack = { nav.popBackStack() }) }
    }
}
