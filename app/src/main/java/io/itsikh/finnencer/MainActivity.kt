package io.itsikh.finnencer

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import io.itsikh.finnencer.bugreport.CrashAutoReporter
import io.itsikh.finnencer.ui.navigation.AppNavHost
import io.itsikh.finnencer.ui.theme.FinnencerTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The single Activity for the app.
 *
 * This template uses a single-Activity architecture with Jetpack Compose and
 * Compose Navigation. All screens are composables mounted inside [AppNavHost].
 *
 * ## Why FragmentActivity (not ComponentActivity)?
 * [androidx.biometric.BiometricPrompt] requires a [FragmentActivity] as its host.
 * [security.BiometricHelper] and [security.ClearDataConfirmationDialog] both need it,
 * so this must stay as [FragmentActivity] for biometric auth to work.
 *
 * ## Hilt
 * [@AndroidEntryPoint] enables field injection into this Activity and into any
 * composable that uses `hiltViewModel()` within [setContent].
 *
 * ## Crash auto-reporting
 * On every launch, [CrashAutoReporter.checkAndReport] is called. It no-ops if there
 * is no pending crash log. If a crash log exists from a previous session, it automatically
 * files a GitHub issue with device info and log content, then clears the log.
 *
 * ## Edge-to-edge
 * [enableEdgeToEdge] is called before [setContent] so the app draws behind the system
 * bars. Each screen is responsible for consuming the window insets via [Scaffold] or
 * explicit padding modifiers.
 *
 * ## Replacing the UI
 * To change the navigation structure, edit [AppNavHost].
 * To change the entry screen, change `startDestination` in [AppNavHost].
 */
@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    @Inject lateinit var crashAutoReporter: CrashAutoReporter

    // Hoisted out of AppNavHost so onNewIntent can forward warm-start
    // deep-link intents (notification taps while the app is already
    // running) into the NavController via handleDeepLink. Cold-start
    // deep links are already handled by the NavHost itself when it
    // reads the activity's initial intent.
    private var navController: NavHostController? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val mainViewModel: MainViewModel = hiltViewModel()
            val themeId by mainViewModel.themeId.collectAsState()
            FinnencerTheme(themeId = themeId) {
                val updatePrompt by mainViewModel.updatePrompt.collectAsState()
                val nav = rememberNavController()
                navController = nav

                AppNavHost(navController = nav)

                when (val prompt = updatePrompt) {
                    is MainViewModel.UpdatePromptState.Available -> {
                        AlertDialog(
                            onDismissRequest = { mainViewModel.dismissUpdatePrompt() },
                            title = { Text("Update Available") },
                            text = { Text("Version ${prompt.info.version} is available. Would you like to download and install it now?") },
                            confirmButton = {
                                Button(onClick = { mainViewModel.downloadAndInstall(prompt.info) }) {
                                    Text("Update Now")
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = { mainViewModel.dismissUpdatePrompt() }) {
                                    Text("Later")
                                }
                            }
                        )
                    }
                    is MainViewModel.UpdatePromptState.Downloading -> {
                        AlertDialog(
                            onDismissRequest = {},
                            title = { Text("Downloading Update") },
                            text = {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    CircularProgressIndicator(Modifier.size(20.dp))
                                    Text("Downloading update, please wait…")
                                }
                            },
                            confirmButton = {}
                        )
                    }
                    else -> {}
                }
            }
        }
        lifecycleScope.launch { crashAutoReporter.checkAndReport() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Activity is launchMode=singleTop, so a notification tap while
        // the app is already running re-enters via onNewIntent rather
        // than onCreate. Forward the intent to the NavController so its
        // navDeepLink on `article/{articleId}` resolves and routes to
        // ArticleDetailScreen.
        setIntent(intent)
        navController?.handleDeepLink(intent)
    }
}
