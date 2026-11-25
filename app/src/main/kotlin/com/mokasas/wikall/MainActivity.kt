package com.mokasas.wikall

import android.Manifest
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject internal lateinit var controller: ConnectionController
    private var selectedMode by mutableStateOf(Mode.HOME)
    private var imported by mutableStateOf<Profile?>(null)
    private var exported by mutableStateOf<Uri?>(null)
    private var note by mutableStateOf("")
    private var resumed by mutableStateOf(false)
    private var attemptedAudio: UUID? = null
    private var microphoneAction: (() -> Unit)? = null

    private val microphone = registerForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        val action = microphoneAction
        microphoneAction = null
        if (allowed) action?.invoke() else note = "اسمح بالميكروفون لإجراء المكالمات الصوتية."
    }
    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) note = "يمكنك تفعيل إشعارات Wi-Call من إعدادات الهاتف لاستقبال تنبيه المكالمات في الخلفية."
    }
    private val openProfile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(::import)
    }
    private val saveProfile = registerForActivityResult(ActivityResultContracts.CreateDocument(PROFILE_MIME)) { uri ->
        if (uri != null) {
            lifecycleScope.launch {
                val result = runCatching {
                    val bytes = controller.export()
                    withContext(Dispatchers.IO) {
                        checkNotNull(contentResolver.openOutputStream(uri, "wt")).use { it.write(bytes) }
                    }
                }
                if (result.isSuccess) {
                    exported = uri
                    note = "شارك الملف خلال خمس دقائق."
                } else note = "تعذّر إنشاء الملف. تحقق من تشغيل الخادم."
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        selectedMode = savedInstanceState?.getString("mode")?.let(Mode::valueOf) ?: Mode.HOME
        setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                MaterialTheme(colorScheme = darkColorScheme(
                    primary = Color(0xFF5DECC2), onPrimary = Color(0xFF102020),
                    background = Color(0xFF101820), surface = Color(0xFF1C2B35),
                )) {
                    val session by controller.state.collectAsState()
                    LaunchedEffect(session.callId, session.callState, resumed) {
                        val incoming = session.callState == CallState.RINGING
                        setShowWhenLocked(incoming || session.callId != null)
                        setTurnScreenOn(incoming)
                        val call = session.callId
                        if (resumed && call != null && session.callState == CallState.ACCEPTED &&
                            attemptedAudio != call && hasMicrophone()) startAudio(call)
                    }
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        Screen(session)
                    }
                }
            }
        }
        if (savedInstanceState == null) handleIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("mode", selectedMode.name)
        super.onSaveInstanceState(outState)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        resumed = true
    }

    override fun onPause() {
        resumed = false
        super.onPause()
    }

    @Composable
    private fun Screen(session: SessionState) {
        val mode = session.mode.takeUnless { it == Mode.HOME } ?: selectedMode
        var addressChoices by remember { mutableStateOf<List<String>?>(null) }
        var manualAction by remember { mutableStateOf<((String) -> Unit)?>(null) }
        var addressInput by remember { mutableStateOf("") }
        var fingerprint by remember { mutableStateOf<Pair<Profile, String?>?>(null) }
        var revoke by remember { mutableStateOf(false) }
        BackHandler(mode != Mode.HOME) {
            if (session.mode == Mode.HOME) selectedMode = Mode.HOME else moveTaskToBack(true)
        }
        Column(
            Modifier.safeDrawingPadding().fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(getString(R.string.app_name), style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.primary)
            when (mode) {
                Mode.HOME -> {
                    Text("مكالمات محلية عبر Wi-Fi أو نقطة الاتصال")
                    Action("وضع الخادم · 1000") { selectedMode = Mode.SERVER }
                    Action("وضع العميل · 1001", true) { selectedMode = Mode.CLIENT }
                }
                Mode.SERVER -> {
                    Text("الخادم المحلي · 1000", style = MaterialTheme.typography.titleLarge)
                    if (session.address != null) {
                        Text("${session.address}:$PORT", style = TextStyle(textDirection = TextDirection.Ltr))
                        session.pin?.let { Text("بصمة الخادم SHA-256\n${groupPin(it)}") }
                        Action("إنشاء ملف .mokprofile") { saveProfile.launch("Wi-Call-1001.mokprofile") }
                        exported?.let { uri -> Action("مشاركة الملف", true) { share(uri) } }
                        Action("إلغاء اقتران العميل", true) { revoke = true }
                    } else if (!session.busy) {
                        Action("تشغيل الخادم") {
                            val addresses = runCatching(controller::listAddresses).getOrDefault(emptyList())
                            when (addresses.size) {
                                0 -> {
                                    addressInput = ""
                                    manualAction = { runSession { controller.start(it) } }
                                }
                                1 -> runSession { controller.start(addresses.single()) }
                                else -> addressChoices = addresses
                            }
                        }
                    }
                }
                Mode.CLIENT -> {
                    Text("العميل المحلي · 1001", style = MaterialTheme.typography.titleLarge)
                    if (session.peer == null && !session.busy) {
                        Action("إعادة الاتصال بالاقتران المحفوظ") { runSession { controller.reconnect() } }
                        Action("استيراد .mokprofile", true) { openProfile.launch(arrayOf("*/*")) }
                        imported?.let { file ->
                            Action("التحقق من البصمة والاتصال") { fingerprint = file to null }
                        }
                        Action("عنوان IP يدوي", true) {
                            addressInput = imported?.host ?: session.address.orEmpty()
                            manualAction = { address ->
                                val file = imported
                                if (file == null) runSession { controller.reconnect(address) }
                                else fingerprint = file to address
                            }
                        }
                        Action("نسيان الاتصال المحفوظ", true) { controller.forget(); imported = null }
                    }
                }
            }
            if (session.status.isNotBlank()) Text(session.status)
            if (session.busy) Action("إلغاء الاتصال", true, ::leave)
            session.peer?.let { peer -> CallPane(session, peer) }
            if (note.isNotBlank()) Text(note)
            if (mode != Mode.HOME) Action("العودة لاختيار الوضع", true, ::leave)
        }

        addressChoices?.let { items ->
            AlertDialog(
                onDismissRequest = { addressChoices = null },
                title = { Text("اختر عنوان الشبكة المحلية") },
                text = { Column {
                    items.forEach { address ->
                        Action(address, true) {
                            addressChoices = null
                            runSession { controller.start(address) }
                        }
                    }
                } },
                confirmButton = { Action("إلغاء", true) { addressChoices = null } },
            )
        }
        manualAction?.let { connect ->
            AlertDialog(
                onDismissRequest = { manualAction = null },
                title = { Text("عنوان IPv4 محلي") },
                text = { OutlinedTextField(addressInput, { addressInput = it }, singleLine = true) },
                confirmButton = {
                    Button(enabled = privateIpv4(addressInput.trim()), onClick = {
                        manualAction = null
                        connect(addressInput.trim())
                    }) { Text("متابعة") }
                },
                dismissButton = { Action("إلغاء", true) { manualAction = null } },
            )
        }
        fingerprint?.let { (file, address) ->
            AlertDialog(
                onDismissRequest = { fingerprint = null },
                title = { Text("تحقق من هوية الخادم") },
                text = { Text("قارن البصمة كاملة مع شاشة الخادم.\n\n${groupPin(file.pin)}") },
                confirmButton = { Action("البصمة مطابقة") {
                    fingerprint = null
                    runSession { controller.connect(file, address) }
                } },
                dismissButton = { Action("إلغاء", true) { fingerprint = null } },
            )
        }
        if (revoke) {
            AlertDialog(
                onDismissRequest = { revoke = false },
                title = { Text("إلغاء اقتران المستخدم 1001") },
                text = { Text("سيحتاج الهاتف الثاني إلى ملف اقتران جديد.") },
                confirmButton = { Action("إلغاء الاقتران") { revoke = false; controller.revoke() } },
                dismissButton = { Action("رجوع", true) { revoke = false } },
            )
        }
    }

    @Composable
    private fun CallPane(session: SessionState, peer: String) {
        Text("المستخدم $peer متصل", style = MaterialTheme.typography.titleMedium)
        val status = when (session.callState) {
            CallState.IDLE -> ""
            CallState.CALLING -> "جارٍ الاتصال بالمستخدم $peer"
            CallState.RINGING -> "مكالمة واردة من $peer"
            CallState.ACCEPTED -> "قُبلت المكالمة. جارٍ بدء الصوت."
            CallState.CONNECTING -> "جارٍ إنشاء اتصال الصوت"
            CallState.CONNECTED -> "المكالمة الصوتية متصلة"
            CallState.RECONNECTING -> "جارٍ استعادة اتصال الصوت"
            CallState.FAILED -> "تعذّر اتصال الصوت"
            CallState.REJECTED -> "رُفضت المكالمة"
            CallState.MISSED -> "انتهت مهلة الإجابة"
            CallState.BUSY -> "المستخدم مشغول"
            CallState.ENDED -> "انتهت المكالمة"
        }
        if (status.isNotEmpty()) Text(status)
        val id = session.callId
        if (session.callState == CallState.RINGING) {
            Action("قبول") {
                withMicrophone {
                    val current = controller.state.value
                    if (current.callId == id && current.callState == CallState.RINGING) controller.decide(SignalType.ACCEPT)
                }
            }
            Action("رفض", true) { controller.decide(SignalType.REJECT) }
        } else if (id != null) {
            if (session.callState == CallState.ACCEPTED) {
                Action("تشغيل الصوت") { withMicrophone { startAudio(id) } }
            }
            if (session.callState == CallState.CONNECTED) {
                Action(if (session.muted) "إلغاء كتم الميكروفون" else "كتم الميكروفون", true, controller::toggleMute)
            }
            Action("إنهاء المكالمة", true) { controller.decide(SignalType.END) }
        } else Action("اتصل بـ $peer") { withMicrophone { controller.dial(peer) } }
        Action("افحص الاتصال", true, controller::probe)
        session.latency?.let { Text("زمن الذهاب والإياب $it مللي ثانية") }
    }

    @Composable
    private fun Action(label: String, secondary: Boolean = false, action: () -> Unit) {
        if (secondary) OutlinedButton(action, Modifier.fillMaxWidth()) { Text(label) }
        else Button(action, Modifier.fillMaxWidth()) { Text(label) }
    }

    private fun withMicrophone(action: () -> Unit) {
        if (hasMicrophone()) action()
        else {
            microphoneAction = action
            microphone.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun hasMicrophone() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun startAudio(id: UUID) {
        if (!resumed || !hasMicrophone() || controller.state.value.callId != id ||
            controller.state.value.callState != CallState.ACCEPTED) return
        attemptedAudio = id
        runCatching {
            startForegroundService(Intent(this, CallSessionService::class.java).putExtra(CALL_ID, id.toString()))
        }.onFailure { note = "تعذّر تشغيل الصوت. أعد المحاولة والتطبيق مفتوح." }
    }

    private fun runSession(action: () -> Unit) {
        note = ""
        runCatching {
            startForegroundService(Intent(this, LocalServerForegroundService::class.java))
            action()
        }.onFailure { note = "تعذّر بدء خدمة الاتصال المحلي." }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !getPreferences(MODE_PRIVATE).getBoolean("notificationAsked", false)) {
            getPreferences(MODE_PRIVATE).edit().putBoolean("notificationAsked", true).apply()
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun leave() {
        controller.stop()
        stopService(Intent(this, LocalServerForegroundService::class.java))
        selectedMode = Mode.HOME
        note = ""
        exported = null
        imported = null
    }

    @Suppress("DEPRECATION")
    private fun handleIntent(intent: Intent) {
        val uri = when (intent.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else intent.getParcelableExtra(Intent.EXTRA_STREAM)
            else -> null
        }
        uri?.let(::import)
    }

    private fun import(uri: Uri) {
        if (uri.scheme != "content") return
        selectedMode = Mode.CLIENT
        lifecycleScope.launch {
            val file = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = checkNotNull(contentResolver.openInputStream(uri)).use { stream ->
                        val buffer = ByteArray(Profile.MAX_SIZE + 1)
                        var size = 0
                        while (size < buffer.size) {
                            val read = stream.read(buffer, size, buffer.size - size)
                            if (read <= 0) break
                            size += read
                        }
                        buffer.copyOf(size)
                    }
                    Profile.read(bytes)
                }.getOrNull()
            }
            imported = file
            note = if (file != null) "طابق بصمة الخادم للاتصال." else "ملف اقتران غير صالح أو منتهي الصلاحية."
        }
    }

    private fun share(uri: Uri) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = PROFILE_MIME
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri("Wi-Call", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, "مشاركة ملف الاتصال"))
    }

    private fun groupPin(pin: String) = pin.chunked(4).chunked(4).joinToString("\n") { it.joinToString(" ") }

    private companion object {
        const val PROFILE_MIME = "application/vnd.mokasas.profile"
    }
}
