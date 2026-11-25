package com.mokasas.wikall

import android.Manifest
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.painterResource
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
    private data class ImportedActivation(val license: Activation, val bytes: ByteArray)
    private data class SharedDocument(val uri: Uri, val mime: String)
    private var imported by mutableStateOf<ImportedActivation?>(null)
    private var request by mutableStateOf<DeviceRequest?>(null)
    private var exported by mutableStateOf<SharedDocument?>(null)
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
    private val openActivation = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { import(it, false) }
    }
    private val openRequest = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { import(it, true) }
    }
    private val saveRequest = registerForActivityResult(ActivityResultContracts.CreateDocument(REQUEST_MIME)) { uri ->
        if (uri != null) {
            saveDocument(uri, REQUEST_MIME) {
                val identity = withContext(Dispatchers.IO) { DeviceIdentity.load() }
                "أرسل طلب الجهاز إلى هاتف الخادم." to identity.request.export(identity.signing)
            }
        }
    }
    private val saveActivation = registerForActivityResult(ActivityResultContracts.CreateDocument(ACTIVATION_MIME)) { uri ->
        val device = request
        if (uri != null && device != null) {
            saveDocument(uri, ACTIVATION_MIME) {
                val (number, bytes) = controller.issue(device)
                request = null
                "صدر تفعيل المستخدم $number. أرسل الملف إلى صاحب الطلب." to bytes
            }
        }
    }

    private fun saveDocument(uri: Uri, mime: String, produce: suspend () -> Pair<String, ByteArray>) {
        lifecycleScope.launch {
            runCatching {
                val (summary, bytes) = produce()
                withContext(Dispatchers.IO) {
                    checkNotNull(contentResolver.openOutputStream(uri, "wt")).use { it.write(bytes) }
                }
                exported = SharedDocument(uri, mime)
                note = summary
            }.onFailure { failure ->
                note = if (failure is CapacityReached) "وصل الخادم إلى الحد الأقصى وهو عشرة مستخدمين."
                    else "تعذّر إعداد الملف. تأكد من مساحة التخزين وحالة الخادم."
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        selectedMode = savedInstanceState?.getString("mode")?.let(Mode::valueOf) ?: Mode.HOME
        setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                MaterialTheme(colorScheme = darkColorScheme(
                    primary = Color(0xFF75F0CA), onPrimary = Color(0xFF06251D),
                    secondary = Color(0xFFADC9FF), onSecondary = Color(0xFF132541),
                    background = Color(0xFF0C1423), surface = Color(0xFF162337),
                    surfaceVariant = Color(0xFF203148), onSurfaceVariant = Color(0xFFD0DDF0),
                    outline = Color(0xFF7D91AE),
                )) {
                    val session by controller.state.collectAsState()
                    LaunchedEffect(session.peer, session.mode) {
                        if (session.mode == Mode.CLIENT && session.peer != null) imported = null
                    }
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
        var revoke by remember { mutableStateOf<String?>(null) }
        BackHandler(mode != Mode.HOME) {
            if (session.mode == Mode.HOME) selectedMode = Mode.HOME else moveTaskToBack(true)
        }
        Column(
            Modifier.safeDrawingPadding().fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Image(painterResource(R.drawable.ic_wikall), contentDescription = null, modifier = Modifier.size(52.dp))
                Column {
                    Text(getString(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
                    Text("مكالمات محلية", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            when (mode) {
                Mode.HOME -> {
                    Panel("اختر دور هذا الهاتف") {
                        Action("إدارة الخادم · $HOST_NUMBER") { selectedMode = Mode.SERVER }
                        Action("الانضمام إلى خادم", true) { selectedMode = Mode.CLIENT }
                    }
                }
                Mode.SERVER -> {
                    Panel("لوحة الخادم · $HOST_NUMBER") {
                        Text(session.status.ifBlank { "وصّل الهواتف بالشبكة نفسها، ثم شغّل الخادم." })
                        val address = session.address
                        if (address != null) {
                            Text("$address:$PORT", style = TextStyle(textDirection = TextDirection.Ltr))
                            Text("المتصلون ${session.onlineUsers.size} · الأرقام المخصصة ${session.devices.size} من $USER_LIMIT")
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
                    session.peer?.takeIf { session.callId != null }?.let { peer ->
                        Panel("المكالمة") { CallPane(session, peer) }
                    }
                    if (session.address != null) {
                        Panel("الأجهزة") {
                            if (session.devices.isEmpty()) Text("استورد طلب الجهاز الذي تريد إضافته.")
                            session.devices.forEach { user ->
                                val connected = user.number in session.onlineUsers
                                Text(
                                    "المستخدم ${user.number} · " + when {
                                        user.revoked -> "التفعيل ملغى"
                                        connected -> "متصل"
                                        else -> "غير متصل"
                                    },
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                if (connected && session.callId == null) {
                                    Action("اتصل بـ ${user.number}") { withMicrophone { controller.dial(user.number) } }
                                }
                                if (!user.revoked) {
                                    Action("إلغاء تفعيل ${user.number}", true) { revoke = user.number }
                                }
                                HorizontalDivider()
                            }
                            Action("استيراد طلب جهاز", true) { openRequest.launch(arrayOf("*/*")) }
                            request?.let { pending ->
                                Text("معرّف الطلب ${pending.serial.take(12)}", style = TextStyle(textDirection = TextDirection.Content))
                                Action("إصدار ملف تفعيل") { saveActivation.launch("Wi-Call-${pending.serial.take(8)}.wiactivation") }
                            }
                            exported?.takeIf { it.mime == ACTIVATION_MIME }?.let { document ->
                                Action("مشاركة ملف التفعيل", true) { share(document) }
                            }
                        }
                    }
                }
                Mode.CLIENT -> {
                    val peer = session.peer
                    Panel(session.number?.let { "رقمي · $it" } ?: "الانضمام إلى خادم") {
                        Text(session.status.ifBlank { "أنشئ طلب جهاز وأرسله لمسؤول الخادم، ثم استورد ملف التفعيل الذي يعطيك إياه." })
                        session.address?.let { Text(it, style = TextStyle(textDirection = TextDirection.Ltr)) }
                        if (peer == null && !session.busy) {
                            imported?.let { current ->
                                Action("اتصل بالخادم · ${current.license.number}") {
                                    runSession { controller.connect(current.license, current.bytes) }
                                }
                            } ?: Action("الاتصال بالتفعيل المحفوظ") { runSession { controller.reconnect() } }
                            Action("عنوان IP يدوي", true) {
                                addressInput = imported?.license?.address ?: session.address.orEmpty()
                                manualAction = { address ->
                                    val file = imported
                                    runSession {
                                        if (file == null) controller.reconnect(address)
                                        else controller.connect(file.license, file.bytes, address)
                                    }
                                }
                            }
                        }
                    }
                    if (peer != null) {
                        Panel("المكالمة") { CallPane(session, peer) }
                    } else if (!session.busy) {
                        Panel("تفعيل هذا الهاتف") {
                            Action("إنشاء طلب جهاز") { saveRequest.launch("Wi-Call.wirequest") }
                            exported?.takeIf { it.mime == REQUEST_MIME }?.let { document ->
                                Action("مشاركة طلب الجهاز", true) { share(document) }
                            }
                            Action("استيراد ملف التفعيل", true) { openActivation.launch(arrayOf("*/*")) }
                            Action("نسيان التفعيل المحفوظ", true) { controller.forget(); imported = null }
                        }
                    }
                }
            }
            if (session.busy) Action("إلغاء الاتصال", true, ::leave)
            if (note.isNotBlank()) Text(note, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
        revoke?.let { number ->
            AlertDialog(
                onDismissRequest = { revoke = null },
                title = { Text("إلغاء تفعيل $number") },
                text = { Text("سينقطع اتصال هذا الجهاز، وسيحتاج إلى ملف تفعيل جديد للعودة بالرقم نفسه.") },
                confirmButton = { Action("إلغاء التفعيل") { revoke = null; controller.revoke(number) } },
                dismissButton = { Action("رجوع", true) { revoke = null } },
            )
        }
    }

    @Composable
    private fun Panel(title: String, content: @Composable () -> Unit) {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                content()
            }
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
        request = null
    }

    @Suppress("DEPRECATION")
    private fun handleIntent(intent: Intent) {
        val uri = when (intent.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else intent.getParcelableExtra(Intent.EXTRA_STREAM)
            else -> null
        }
        uri?.let { import(it, intent.type == REQUEST_MIME) }
    }

    private fun import(uri: Uri, isRequest: Boolean) {
        if (uri.scheme != "content") return
        if (isRequest && controller.state.value.mode != Mode.SERVER) {
            selectedMode = Mode.SERVER
            note = "شغّل الخادم ثم اختر استيراد طلب جهاز."
            return
        }
        if (!isRequest) {
            if (controller.state.value.mode != Mode.HOME) controller.stop()
            selectedMode = Mode.CLIENT
            imported = null
        } else request = null
        lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val bytes = readDocument(uri)
                    if (isRequest) DeviceRequest.read(bytes)
                    else {
                        val identity = DeviceIdentity.load()
                        ImportedActivation(Activation.read(bytes, identity.decryption, identity.request.serial), bytes)
                    }
                }
            }.onSuccess { file ->
                when (file) {
                    is DeviceRequest -> { request = file; note = "تحقق أن الطلب وصل من الهاتف الذي تريد إضافته." }
                    is ImportedActivation -> {
                        imported = file
                        note = ""
                        runSession { controller.connect(file.license, file.bytes) }
                    }
                    else -> Unit
                }
            }.onFailure {
                note = if (isRequest) "ملف طلب الجهاز غير صالح."
                    else "تعذّر فتح التفعيل. استخدم الملف الصادر لهذا الهاتف والنسخة الحالية من التطبيق."
            }
        }
    }

    private fun readDocument(uri: Uri): ByteArray =
        checkNotNull(contentResolver.openInputStream(uri)).use { stream ->
            val buffer = ByteArray(FILE_LIMIT + 1)
            var size = 0
            while (size < buffer.size) {
                val read = stream.read(buffer, size, buffer.size - size)
                if (read <= 0) break
                size += read
            }
            buffer.copyOf(size)
        }

    private fun share(document: SharedDocument) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = document.mime
            putExtra(Intent.EXTRA_STREAM, document.uri)
            clipData = ClipData.newRawUri("Wi-Call", document.uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, "مشاركة الملف"))
    }

    private companion object {
        const val REQUEST_MIME = "application/vnd.wikall.request"
        const val ACTIVATION_MIME = "application/vnd.wikall.activation"
    }
}
