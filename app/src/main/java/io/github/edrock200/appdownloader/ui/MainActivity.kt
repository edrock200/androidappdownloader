package io.github.edrock200.appdownloader.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import io.github.edrock200.appdownloader.ui.theme.AppTheme

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            AppTheme {
                MainScreen(viewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        // Runs both on a fresh start and when the app is brought back from the background,
        // which is how TVs usually "open" an app that is already running.
        viewModel.checkSelfUpdateOnLaunch()
        viewModel.checkAllOnLaunch()
    }

    override fun onResume() {
        super.onResume()
        // Apps may have been installed/removed, and the install permission changed, while we were away.
        viewModel.refreshInstalled()
        // An install prompt dismissed without an answer leaves no result; free those cards.
        viewModel.reconcileSessions()
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND) {
            val text = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim()
            if (!text.isNullOrBlank()) {
                // Pull the first URL out of shared text such as "Check this out: https://..."
                val url = Regex("https?://\\S+").find(text)?.value ?: text
                viewModel.sharedText.value = url
            }
        }
    }
}
