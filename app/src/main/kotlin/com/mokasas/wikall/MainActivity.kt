package com.mokasas.wikall

import android.Manifest
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.AtomicFile
import androidx.core.content.FileProvider
import java.io.File
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.platform.testTag
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
    private data class SharedDocument(val uri: Uri, val kind: Transfer)
    private var imported by mutableStateOf<ImportedActivation?>(null)
    private var request by mutableStateOf<DeviceRequest?>(null)
    private var exported by mutableStateOf<SharedDocument?>(null)
    private var note by mutableStateOf("")
    private var preparingFile by mutableStateOf(false)
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
    private fun shareRequest() = prepareShare(Transfer.REQUEST) {
        val identity = withContext(Dispatchers.IO) { DeviceIdentity.load() }
        val serial = identity.request.serial.take(8)
        val file = withContext(Dispatchers.IO) {
            writeShareFile(File(cacheDir, "exports/requests/Wi-Call-Request-$serial.zip"),
                Transfer.REQUEST, identity.request.export(identity.signing))
        }
        "أرسل طلب التفعيل إلى مسؤول الخادم." to file
    }

    private fun issueAndShare(device: DeviceRequest) = prepareShare(Transfer.ACTIVATION) {
        val (number, bytes) = controller.issue(device)
        val file = withContext(Dispatchers.IO) {
            writeShareFile(File(filesDir, "exports/activations/Wi-Call-$number.zip"), Transfer.ACTIVATION, bytes)
        }
        request = null
        "حُفظ ملف Wi-Call-$number.zip للمستخدم $number." to file
    }

    private fun shareIssued(number: String) = prepareShare(Transfer.ACTIVATION) {
        require(number in userNumbers)
        val file = File(filesDir, "exports/activations/Wi-Call-$number.zip")
        check(withContext(Dispatchers.IO) { file.isFile })
        "ملف التفعيل المشفّر للمستخدم $number." to file
    }

    private fun writeShareFile(path: File, kind: Transfer, content: ByteArray): File {
        check(path.parentFile?.let { it.isDirectory || it.mkdirs() } == true)
        val storage = AtomicFile(path)
        val output = storage.startWrite()
        try {
            output.write(packTransfer(kind, content))
            storage.finishWrite(output)
        } catch (failure: Exception) {
            storage.failWrite(output)
            throw failure
        }
        return path
    }

    private fun prepareShare(kind: Transfer, produce: suspend () -> Pair<String, File>) {
        if (preparingFile) return
        preparingFile = true
        lifecycleScope.launch {
            try {
                val (summary, file) = produce()
                val uri = FileProvider.getUriForFile(this@MainActivity, "$packageName.activation-files", file)
                val document = SharedDocument(uri, kind)
                exported = document
                note = summary
                share(document)
            } catch (_: CapacityReached) {
                note = "وصل الخادم إلى الحد الأقصى وهو عشرة مستخدمين."
            } catch (_: Exception) {
                note = "تعذّر إعداد الملف أو فتح المشاركة."
            } finally {
                preparingFile = false
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
        var showDialPad by remember { mutableStateOf(false) }
        var editDirectory by remember { mutableStateOf(false) }
        var directoryInput by remember { mutableStateOf("") }
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
                    Text(if (session.internetReady) "الاتصال عبر الإنترنت جاهز" else "مكالمات Wi-Call",
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                        }
                        if ((address != null || session.internetReady) && session.callId == null)
                            Action("شاشة الاتصال") { showDialPad = true }
                        if (address == null && !session.busy && session.number == null) {
                            Action("تشغيل الخادم") {
                                val addresses = runCatching(controller::listAddresses).getOrDefault(emptyList())
                                when (addresses.size) {
                                    0 -> {
                                        if (controller.internetEndpoint().isNotBlank()) runSession { controller.start(null) }
                                        else {
                                            addressInput = ""
                                            manualAction = { runSession { controller.start(it) } }
                                        }
                                    }
                                    1 -> runSession { controller.start(addresses.single()) }
                                    else -> addressChoices = addresses
                                }
                            }
                        }
                    }
                    session.publicKey?.let { key ->
                        Action("نسخ المفتاح العام لإعداد خدمة الإنترنت", true) {
                            getSystemService(android.content.ClipboardManager::class.java)
                                .setPrimaryClip(android.content.ClipData.newPlainText("Wi-Call public key", key))
                        }
                    }
                    session.peer?.takeIf { session.callState != CallState.IDLE }?.let { peer ->
                        Panel("المكالمة") { CallPane(session, peer) }
                    }
                    if (session.number != null) {
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
                                if (!user.revoked && (connected || session.internetReady) && session.callId == null) {
                                    Action("اتصل بـ ${user.number}") { withMicrophone { controller.dial(user.number) } }
                                }
                                if (!user.revoked) {
                                    Action("مشاركة تفعيل ${user.number}", true) { shareIssued(user.number) }
                                    Action("إلغاء تفعيل ${user.number}", true) { revoke = user.number }
                                }
                                HorizontalDivider()
                            }
                            Action("استيراد طلب التفعيل", true) { openRequest.launch(arrayOf("*/*")) }
                            request?.let { pending ->
                                Text("معرّف الطلب ${pending.serial.take(12)}", style = TextStyle(textDirection = TextDirection.Content))
                                if (session.address != null) Action("إصدار التفعيل ومشاركته") { issueAndShare(pending) }
                            }
                        }
                    }
                }
                Mode.CLIENT -> {
                    val peer = session.peer
                    Panel(session.number?.let { "رقمي · $it" } ?: "الانضمام إلى خادم") {
                        Text(session.status.ifBlank { "اضغط طلب التفعيل وشاركه مع مسؤول الخادم، ثم استورد ملف التفعيل الذي يصدره لك." })
                        session.address?.let { Text(it, style = TextStyle(textDirection = TextDirection.Ltr)) }
                        if (peer == null && !session.busy) {
                            imported?.let { current ->
                                Action("اتصل بالخادم · ${current.license.number}") {
                                    runSession { controller.connect(current.license, current.bytes) }
                                }
                            } ?: Action("الاتصال بالتفعيل المحفوظ") { runSession { controller.reconnect() } }
                            Action("تغيير عنوان الخادم", true) {
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
                    if (peer != null || session.internetReady && !session.busy) {
                        Panel("المكالمة") {
                            if (session.callId == null) Action("شاشة الاتصال") { showDialPad = true }
                            if (peer != null) CallPane(session, peer)
                        }
                    } else if (!session.busy) {
                        Panel("تفعيل هذا الهاتف") {
                            Action("طلب التفعيل", action = ::shareRequest)
                            exported?.takeIf { it.kind == Transfer.REQUEST }?.let { document ->
                                Action("مشاركة طلب الجهاز", true) { share(document) }
                            }
                            Action("استيراد ملف التفعيل", true) { openActivation.launch(arrayOf("*/*")) }
                            Action("نسيان التفعيل المحفوظ", true) { controller.forget(); imported = null }
                        }
                    }
                }
            }
            if (session.missed.isNotEmpty()) Panel("المكالمات الفائتة") {
                session.missed.forEach { item ->
                    val time = android.text.format.DateUtils.getRelativeTimeSpanString(item.endedAt)
                    Text("${item.peer} · $time")
                    if (session.callId == null && (session.internetReady || session.address != null && session.peer != null)) {
                        Action("اتصل بـ ${item.peer}", true) { withMicrophone { controller.dial(item.peer) } }
                    }
                }
            }
            if (session.callId == null) Action("إعداد الاتصال عبر الإنترنت", true) {
                directoryInput = controller.internetEndpoint()
                editDirectory = true
            }
            if (session.busy) Action("إلغاء الاتصال", true, ::leave)
            if (note.isNotBlank()) Text(note, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (mode != Mode.HOME) Action("العودة لاختيار الوضع", true, ::leave)
        }

        if (editDirectory) AlertDialog(
            onDismissRequest = { editDirectory = false },
            title = { Text("خدمة الاتصال عبر الإنترنت") },
            text = { OutlinedTextField(directoryInput, { directoryInput = it },
                label = { Text("رابط HTTPS للخدمة") }, singleLine = true) },
            confirmButton = {
                Button(enabled = directoryInput.isBlank() || directoryUrl(directoryInput) != null, onClick = {
                    controller.configureInternet(directoryInput)
                    editDirectory = false
                }) { Text("حفظ") }
            },
            dismissButton = { Action("إلغاء", true) { editDirectory = false } },
        )

        if (showDialPad) {
            DialPad(
                ownNumber = session.number,
                onClose = { showDialPad = false },
                onCall = { number ->
                    showDialPad = false
                    withMicrophone { controller.dial(number) }
                },
            )
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
        Text("الرقم $peer", style = MaterialTheme.typography.titleMedium)
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
            CallState.UNAVAILABLE -> "الرقم $peer غير متصل"
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
                Action(if (session.speaker) "مسار الصوت التلقائي" else "مكبر الصوت", true, controller::toggleSpeaker)
                Action(if (session.muted) "إلغاء كتم الميكروفون" else "كتم الميكروفون", true, controller::toggleMute)
            }
            Action("إنهاء المكالمة", true) { controller.decide(SignalType.END) }
        }
        if (session.mode == Mode.CLIENT || peer in session.onlineUsers) {
            Action("افحص الاتصال", true, controller::probe)
        }
        if (session.ice.isNotBlank() && session.callState in setOf(CallState.CONNECTING, CallState.RECONNECTING, CallState.FAILED))
            Text("مسار الصوت · ${session.ice}")
        session.latency?.let { Text("زمن الذهاب والإياب $it مللي ثانية") }
    }

    @Composable
    private fun DialPad(ownNumber: String?, onClose: () -> Unit, onCall: (String) -> Unit) {
        var digits by remember { mutableStateOf("") }
        val available = validNumber(digits) && digits != ownNumber
        AlertDialog(
            onDismissRequest = onClose,
            title = { Text("طلب رقم") },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        digits.ifEmpty { "أدخل خمسة أرقام" },
                        style = MaterialTheme.typography.headlineMedium.copy(textDirection = TextDirection.Ltr),
                    )
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        listOf("123", "456", "789").forEach { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                row.forEach { digit ->
                                    OutlinedButton(
                                        onClick = { if (digits.length < 5) digits += digit },
                                        modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("dial-digit-$digit"),
                                    ) { Text(digit.toString()) }
                                }
                            }
                        }
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.widthIn(min = 208.dp),
                        ) {
                            OutlinedButton(
                                onClick = { if (digits.length < 5) digits += '0' },
                                modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("dial-digit-0"),
                            ) { Text("0") }
                            OutlinedButton(
                                onClick = { digits = digits.dropLast(1) },
                                enabled = digits.isNotEmpty(),
                                modifier = Modifier.weight(2f).heightIn(min = 48.dp),
                            ) { Text("حذف") }
                        }
                    }
                }
            },
            confirmButton = { Button(enabled = available, onClick = { onCall(digits) }) { Text("اتصل") } },
            dismissButton = { OutlinedButton(onClick = onClose) { Text("إغلاق") } },
        )
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
        if (intent.getStringExtra("wi_missed") == "1") runSession(controller::resumeMissed)
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
            note = "شغّل الخادم ثم اختر استيراد طلب التفعيل."
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
                    val kind = if (isRequest) Transfer.REQUEST else Transfer.ACTIVATION
                    val archive = checkNotNull(contentResolver.openInputStream(uri)).use { boundedRead(it, ARCHIVE_LIMIT) }
                    val bytes = unpackTransfer(kind, archive)
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

    private fun share(document: SharedDocument) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, document.uri)
            clipData = ClipData.newRawUri("Wi-Call", document.uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, "مشاركة الملف"))
    }

    private companion object {
        const val REQUEST_MIME = "application/vnd.wikall.request"
    }
}
