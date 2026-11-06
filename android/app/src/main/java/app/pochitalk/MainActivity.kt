package app.pochitalk

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.pochitalk.engine.PochiEngine
import app.pochitalk.engine.UiEvent
import app.pochitalk.ui.Actions
import app.pochitalk.ui.PochiRoot
import app.pochitalk.ui.PochiTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var afterPermissions: (() -> Unit)? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            // 許可されなくても続行する (聞くだけの参加になる。話そうとしたときにまた頼む)
            PochiEngine.refreshMicPermission()
            val next = afterPermissions
            afterPermissions = null
            next?.invoke()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                PochiEngine.events.collect { event ->
                    if (event is UiEvent.RequestMic) withPermissions { }
                }
            }
        }

        val actions = Actions(
            join = { key -> withPermissions { PochiEngine.join(key) } },
            create = { withPermissions { PochiEngine.createRoom() } },
            leave = { PochiEngine.leave() },
            share = { shareInvite() },
            micTest = { on -> if (on) withPermissions { PochiEngine.setMicTest(true) } else PochiEngine.setMicTest(false) },
        )
        setContent {
            PochiTheme {
                val state by PochiEngine.state.collectAsStateWithLifecycle()
                PochiRoot(state, actions)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        PochiEngine.onAppForeground()
    }

    override fun onStop() {
        PochiEngine.onAppBackground()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        PochiEngine.refreshMicPermission()
    }

    /** 招待リンク (https://…/?r=ハッシュ または pochitalk://join?r=ハッシュ) を受け取る。自動では入室しない。 */
    private fun handleIntent(intent: Intent?) {
        val uri = intent?.data ?: return
        val key = uri.getQueryParameter("r")?.trim()?.lowercase()
        if (key != null) PochiEngine.setPendingKey(key)
    }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    /** マイクと通知の許可をまとめて頼んでから action を実行する。 */
    private fun withPermissions(action: () -> Unit) {
        val needed = ArrayList<String>()
        if (!granted(Manifest.permission.RECORD_AUDIO)) needed.add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS)) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (needed.isEmpty()) {
            action()
        } else {
            afterPermissions = action
            permissionLauncher.launch(needed.toTypedArray())
        }
    }

    private fun shareInvite() {
        val url = PochiEngine.inviteUrl() ?: return
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, "ぽちっと通話で話しましょう: $url")
        startActivity(Intent.createChooser(send, null))
    }
}
