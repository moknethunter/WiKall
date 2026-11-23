package com.mokasas.wikall

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private enum class Mode { HOME, SERVER, CLIENT }
    private var mode = Mode.HOME
    private lateinit var controller: ConnectionController
    private lateinit var content: LinearLayout
    private var profile: Profile? = null
    private var exportedUri: Uri? = null
    private var foreground = false
    private var ringtone: Ringtone? = null
    private var ringing = false
    private var message = ""
    private val backgroundColor = Color.rgb(16, 24, 32)
    private val panel = Color.rgb(28, 43, 53)
    private val green = Color.rgb(93, 236, 194)
    private val ink = Color.rgb(239, 246, 247)
    private val muted = Color.rgb(178, 198, 205)
    private val vibrator: Vibrator? get() = getSystemService(Vibrator::class.java)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        controller = ConnectionController(::render)
        val scroller = ScrollView(this).apply { isFillViewport = true; setBackgroundColor(backgroundColor) }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(22), dp(28), dp(22), dp(22))
        }
        scroller.addView(content)
        setContentView(scroller)
        if (intent.action == Intent.ACTION_VIEW) intent.data?.let(::import) else render()
    }

    override fun onStart() {
        super.onStart()
        foreground = true
        render()
    }

    override fun onStop() {
        foreground = false
        ring(false)
        super.onStop()
    }

    override fun onDestroy() {
        if (::controller.isInitialized) controller.close()
        ring(false)
        super.onDestroy()
    }

    @Deprecated("Android Activity back callback")
    override fun onBackPressed() {
        if (mode == Mode.HOME) super.onBackPressed() else home()
    }

    private fun home() {
        mode = Mode.HOME
        controller.stop()
        message = ""
        profile = null
        exportedUri = null
        render()
    }

    private fun render() {
        if (!::content.isInitialized) return
        ring(foreground && controller.callState == CallState.RINGING)
        content.removeAllViews()
        text("WiKall", 36, green, true)
        text("MokAsas Local Voice", 17, ink)
        when (mode) {
            Mode.HOME -> {
                paragraph("اتصال محلي بين هاتفين", "وصّل الهاتفين بشبكة Wi‑Fi واحدة أو بنقطة اتصال الهاتف الأول.")
                button("تشغيل كخادم · 1000") { mode = Mode.SERVER; render() }
                button("استخدام ملف تعريف · 1001", secondary = true) { mode = Mode.CLIENT; render() }
                text("إصدار اختبار الاتصال والدعوات", 14, muted)
            }
            Mode.SERVER -> {
                paragraph("الخادم المحلي · 1000", controller.status.ifBlank { "شغّل الخادم من الهاتف الأول." })
                if (controller.hosting) {
                    controller.pin?.let { text("بصمة الخادم (SHA‑256)\n${groupPin(it)}", 13, muted, ltr = true) }
                    button("إنشاء وحفظ .mokprofile") { createProfile() }
                    exportedUri?.let { uri -> button("مشاركة الملف المحفوظ", secondary = true) { share(uri) } }
                    button("إيقاف الخادم", secondary = true) { controller.stop(); exportedUri = null; message = ""; render() }
                } else if (!controller.busy) {
                    button("تشغيل الخادم") { chooseAddress() }
                }
                session("1001")
            }
            Mode.CLIENT -> {
                paragraph("العميل المحلي · 1001", controller.status.ifBlank { "استورد الملف الذي أنشأه الهاتف الأول." })
                if (!controller.connected && !controller.busy) {
                    button("استيراد .mokprofile") {
                        @Suppress("DEPRECATION")
                        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE)
                            type = "*/*"
                        }, IMPORT)
                    }
                    profile?.let { file ->
                        text("عنوان الخادم  ${file.host}:$PORT", 15, muted, ltr = true)
                        button("تحقق من البصمة واتصل") { approve(file, file.host) }
                        button("استخدام عنوان IP يدويًا", secondary = true) {
                            input("عنوان IP للخادم", file.host) { address ->
                                if (privateIpv4(address)) approve(file, address)
                                else note("أدخل عنوان IPv4 محليًا صالحًا.")
                            }
                        }
                    }
                }
                if (controller.connected) button("قطع الاتصال", secondary = true) { controller.stop() }
                session("1000")
            }
        }
        if (message.isNotBlank()) text(message, 15, green)
        if (mode != Mode.HOME) button("العودة لاختيار الوضع", secondary = true) { home() }
    }

    private fun session(peer: String) {
        if (!controller.connected) return
        controller.latency?.let { paragraph("نجح فحص الاتصال", "زمن الذهاب والإياب: $it مللي ثانية.") }
        when (controller.callState) {
            CallState.IDLE -> Unit
            CallState.CALLING -> paragraph("جارٍ إرسال الدعوة", "انتظر رد المستخدم $peer.")
            CallState.RINGING -> {
                paragraph("مكالمة اختبار واردة", "المتصل $peer")
                button("قبول") { controller.decide(SignalType.ACCEPT) }
                button("رفض", secondary = true) { controller.decide(SignalType.REJECT) }
            }
            CallState.ACCEPTED -> paragraph("تم قبول الدعوة", "نجح اختبار الإشارات بين الهاتفين.")
            CallState.REJECTED -> paragraph("رُفضت الدعوة", "يمكنك إعادة الاختبار.")
            CallState.MISSED -> paragraph("انتهت مهلة الدعوة", "لم يُجب الطرف الآخر خلال المهلة.")
            CallState.BUSY -> paragraph("الطرف الآخر مشغول", "جرّب بعد انتهاء الدعوة الحالية.")
            CallState.ENDED -> paragraph("انتهت الدعوة", "يمكنك بدء دعوة جديدة.")
        }
        if (controller.hasCall && controller.callState != CallState.RINGING) {
            button("إنهاء الدعوة", secondary = true) { controller.decide(SignalType.END) }
        }
        if (!controller.hasCall) button("إرسال دعوة إلى $peer") { controller.dial() }
        button("افحص الاتصال", secondary = true) { message = ""; controller.probe(); render() }
    }

    private fun chooseAddress() {
        val addresses = runCatching(controller::listAddresses).getOrDefault(emptyList())
        when (addresses.size) {
            0 -> input("أدخل عنوان الهاتف على الشبكة المحلية", "") { address ->
                if (privateIpv4(address)) controller.start(address)
                else note("لم يظهر عنوان Wi‑Fi. وصّل الهاتف بالشبكة أو شغّل نقطة الاتصال.")
            }
            1 -> controller.start(addresses.single())
            else -> AlertDialog.Builder(this).setTitle("اختر عنوان Wi‑Fi أو نقطة الاتصال")
                .setItems(addresses.toTypedArray()) { _, index -> controller.start(addresses[index]) }.show()
        }
    }

    private fun approve(file: Profile, address: String) {
        AlertDialog.Builder(this)
            .setTitle("تحقق من هوية الخادم")
            .setMessage("قارن البصمة كاملة مع البصمة المعروضة على الهاتف الأول.\n\n${groupPin(file.pin)}")
            .setPositiveButton("البصمة مطابقة · اتصل") { _, _ -> message = ""; controller.connect(file, address) }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun createProfile() {
        @Suppress("DEPRECATION")
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = MIME
            putExtra(Intent.EXTRA_TITLE, "MokAsas-1001.mokprofile")
        }, EXPORT)
    }

    @Deprecated("Activity result callback retained for Android 10 compatibility")
    override fun onActivityResult(request: Int, result: Int, data: Intent?) {
        super.onActivityResult(request, result, data)
        val uri = data?.data ?: return
        if (result != RESULT_OK) return
        when (request) {
            IMPORT -> import(uri)
            EXPORT -> runCatching {
                val bytes = controller.export()
                requireNotNull(contentResolver.openOutputStream(uri, "wt")).use { it.write(bytes) }
                exportedUri = uri
                note("حُفظ الملف. شاركه مع الهاتف الثاني خلال خمس دقائق.")
            }.onFailure { note("تعذّر حفظ الملف. تحقق من تشغيل الخادم وأعد المحاولة.") }
        }
    }

    private fun import(uri: Uri) {
        if (uri.scheme != "content") return
        mode = Mode.CLIENT
        controller.stop()
        profile = null
        runCatching {
            val bytes = requireNotNull(contentResolver.openInputStream(uri)).use { stream ->
                val buffer = ByteArray(Profile.MAX_SIZE + 1)
                var size = 0
                while (size < buffer.size) {
                    val chunk = stream.read(buffer, size, buffer.size - size)
                    if (chunk <= 0) break
                    size += chunk
                }
                buffer.copyOf(size)
            }
            profile = Profile.read(bytes)
            note("الملف سليم. طابق بصمة الخادم للاتصال.")
        }.onFailure { note("ملف غير صالح أو معدّل أو منتهي الصلاحية.") }
    }

    private fun share(uri: Uri) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = MIME
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = android.content.ClipData.newRawUri("MokAsas", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { startActivity(Intent.createChooser(intent, "مشاركة ملف الاتصال")) }
            .onFailure { Toast.makeText(this, "شارك الملف من تطبيق الملفات.", Toast.LENGTH_SHORT).show() }
    }

    private fun ring(active: Boolean) {
        if (ringing == active) return
        ringing = active
        if (active) {
            runCatching {
                ringtone = RingtoneManager.getRingtone(this, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE))
                ringtone?.isLooping = true
                ringtone?.play()
            }
            runCatching { vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 450, 600), 0)) }
        } else {
            ringtone?.stop()
            ringtone = null
            vibrator?.cancel()
        }
    }

    private fun input(title: String, initial: String, onAccept: (String) -> Unit) {
        val field = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_PHONE
            textDirection = View.TEXT_DIRECTION_LTR
            setSingleLine()
            setText(initial)
        }
        AlertDialog.Builder(this).setTitle(title).setView(field)
            .setPositiveButton("متابعة") { _, _ -> onAccept(field.text.toString().trim()) }
            .setNegativeButton("إلغاء", null).show()
    }

    private fun paragraph(title: String, description: String) {
        text(title, 21, ink, true)
        text(description, 16, muted)
    }

    private fun text(value: String, size: Int, color: Int, bold: Boolean = false, ltr: Boolean = false) {
        content.addView(TextView(this).apply {
            text = value
            textSize = size.toFloat()
            setTextColor(color)
            setPadding(dp(2), dp(5), dp(2), dp(8))
            if (bold) setTypeface(typeface, Typeface.BOLD)
            if (ltr) textDirection = View.TEXT_DIRECTION_LTR
        })
    }

    private fun button(label: String, secondary: Boolean = false, action: () -> Unit) {
        val shape = GradientDrawable().apply {
            cornerRadius = dp(16).toFloat()
            setColor(if (secondary) panel else green)
        }
        val button = Button(this).apply {
            text = label
            textSize = 16f
            isAllCaps = false
            setTextColor(if (secondary) ink else backgroundColor)
            background = shape
            backgroundTintList = null
            setPadding(dp(14), dp(8), dp(14), dp(8))
            setOnClickListener { action() }
        }
        content.addView(button, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(6), 0, dp(6)) })
    }

    private fun note(text: String) { message = text; render() }
    private fun groupPin(pin: String): String = pin.chunked(4).chunked(4).joinToString("\n") { it.joinToString(" ") }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val MIME = "application/vnd.mokasas.profile"
        private const val IMPORT = 100
        private const val EXPORT = 101
    }
}
