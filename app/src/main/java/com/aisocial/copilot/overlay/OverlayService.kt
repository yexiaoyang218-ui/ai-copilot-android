package com.aisocial.copilot.overlay

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.aisocial.copilot.AiCopilotApp
import com.aisocial.copilot.MainActivity
import com.aisocial.copilot.R
import com.aisocial.copilot.capture.CaptureBus
import com.aisocial.copilot.capture.ScreenCaptureActivity
import com.aisocial.copilot.core.BitmapUtils
import com.aisocial.copilot.core.NetResult
import com.aisocial.copilot.data.prefs.SettingsRepository
import com.aisocial.copilot.di.AppContainer
import com.aisocial.copilot.domain.model.GenerateInput
import com.aisocial.copilot.domain.model.ImagePayload
import com.aisocial.copilot.domain.model.Persona
import com.aisocial.copilot.domain.model.ReplySuggestion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.min

/**
 * Draggable floating ball + quick panel.
 *
 * Everything lives in a system overlay window, so the ball stays on top of WeChat /
 * QQ / Telegram and can start the capture flow without switching apps.
 */
class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var settings: SettingsRepository
    private lateinit var container: AppContainer
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var ballView: View? = null
    private var ballParams: WindowManager.LayoutParams? = null
    private var panelView: View? = null
    private var panelParams: WindowManager.LayoutParams? = null

    /** True while a screenshot is being taken so the ball never lands in the frame. */
    private var hiddenForCapture = false
    private var hiddenByUser = false
    private var hiddenForResult = false
    private var ballAlpha = 0.92f

    // ---- quick reply flow (capture -> OCR -> 3 options -> fill the chat box) ----
    private var awaitingCapture = false
    private var flowJob: Job? = null
    private var statusView: View? = null
    private var statusText: TextView? = null
    private var resultView: View? = null
    private var resultParams: WindowManager.LayoutParams? = null
    private var resultAlpha = 1f
    private var resultScale = 1f
    private var textInputView: View? = null
    private var textInputParams: WindowManager.LayoutParams? = null
    private var lastQuickContext = ""
    private var lastQuickPersona: Persona = Persona(name = "默认")

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WindowManager::class.java)
        container = (application as AiCopilotApp).container
        settings = container.settingsRepository
        instance = this
        ensureChannel()
        observeDrafts()
        observeOverlayPrefs()
    }

    /** 结果面板的透明度与缩放跟设置同步，改完立刻生效。 */
    private fun observeOverlayPrefs() {
        scope.launch {
            settings.preferences.collect { prefs ->
                resultAlpha = prefs.overlayResultAlpha
                resultScale = prefs.overlayResultScale
                resultView?.alpha = resultAlpha
            }
        }
    }

    /** Picks up the crop produced by [ScreenCaptureActivity] in overlay mode. */
    private fun observeDrafts() {
        scope.launch {
            CaptureBus.draft.collect { draft ->
                if (draft == null || !awaitingCapture) return@collect
                awaitingCapture = false
                CaptureBus.consumeDraft()
                startQuickFlow(draft.bitmap)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundSafely()

        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (ballView == null) {
            scope.launch { attachBall() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        removePanel()
        hideStatus()
        hideQuickResults()
        flowJob?.cancel()
        ballView?.let { runCatching { windowManager.removeView(it) } }
        ballView = null
        ballParams = null
        scope.cancel()
        if (instance === this) instance = null
    }

    // ------------------------------------------------------------------ ball

    private suspend fun attachBall() {
        val prefs = runCatching { settings.preferences.first() }.getOrNull()
        ballAlpha = prefs?.overlayAlpha ?: 0.92f

        val sizePx = dp(BALL_SIZE_DP)
        val screen = screenSize()
        val view = createBallView(sizePx)
        view.alpha = ballAlpha
        view.visibility = if (hiddenForCapture || hiddenByUser) View.GONE else View.VISIBLE

        val params = WindowManager.LayoutParams(
            sizePx,
            sizePx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            val savedX = prefs?.overlayX ?: -1
            val savedY = prefs?.overlayY ?: -1
            if (savedX >= 0 && savedY >= 0 && savedX < screen.x && savedY < screen.y) {
                x = savedX
                y = savedY
            } else {
                x = screen.x - sizePx - dp(6)
                y = (screen.y * 0.62f).toInt()
            }
        }

        attachBallTouch(view, params)
        runCatching { windowManager.addView(view, params) }
        ballView = view
        ballParams = params
    }

    private fun createBallView(sizePx: Int): View {
        val container = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#FF2C4260"))
                setStroke(dp(1), Color.parseColor("#66A9C4E8"))
            }
            elevation = dp(6).toFloat()
        }
        val label = TextView(this).apply {
            text = "AI"
            setTextColor(Color.WHITE)
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        container.addView(
            label,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ).apply { gravity = Gravity.CENTER }
        )
        container.contentDescription = getString(R.string.app_name)
        return container
    }

    private fun attachBallTouch(view: View, params: WindowManager.LayoutParams) {
        var startX = 0
        var startY = 0
        var downRawX = 0f
        var downRawY = 0f
        var downAt = 0L
        var dragged = false

        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    downRawX = event.rawX
                    downRawY = event.rawY
                    downAt = SystemClock.uptimeMillis()
                    dragged = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downRawX).toInt()
                    val dy = (event.rawY - downRawY).toInt()
                    if (abs(dx) > dp(5) || abs(dy) > dp(5)) dragged = true
                    if (dragged) {
                        val screen = screenSize()
                        params.x = (startX + dx).coerceIn(0, (screen.x - params.width).coerceAtLeast(0))
                        params.y = (startY + dy).coerceIn(0, (screen.y - params.height).coerceAtLeast(0))
                        runCatching { windowManager.updateViewLayout(view, params) }
                        removePanel()
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    val quickTap = !dragged && SystemClock.uptimeMillis() - downAt < 320L
                    if (quickTap) {
                        togglePanel()
                    } else {
                        snapToEdge(view, params)
                    }
                    true
                }

                else -> false
            }
        }
    }

    private fun snapToEdge(view: View, params: WindowManager.LayoutParams) {
        val screen = screenSize()
        val target = if (params.x + params.width / 2 < screen.x / 2) 0 else screen.x - params.width
        val animator = ValueAnimator.ofInt(params.x, target)
        animator.duration = 180L
        animator.addUpdateListener { animation ->
            params.x = animation.animatedValue as Int
            runCatching { windowManager.updateViewLayout(view, params) }
        }
        animator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                persistPosition(params.x, params.y)
            }
        })
        animator.start()
    }

    private fun persistPosition(x: Int, y: Int) {
        scope.launch { runCatching { settings.setOverlayPosition(x, y) } }
    }

    // ----------------------------------------------------------------- panel

    private fun togglePanel() {
        if (panelView != null) removePanel() else showPanel()
    }

    private fun showPanel() {
        val anchor = ballParams ?: return
        val panel = createPanelView()
        panel.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val panelWidth = panel.measuredWidth.coerceAtLeast(dp(180))
        val panelHeight = panel.measuredHeight
        val screen = screenSize()
        val gap = dp(10)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = if (anchor.x < screen.x / 2) {
                (anchor.x + anchor.width + gap).coerceAtMost((screen.x - panelWidth).coerceAtLeast(0))
            } else {
                (anchor.x - panelWidth - gap).coerceAtLeast(0)
            }
            y = anchor.y.coerceIn(dp(24), (screen.y - panelHeight - dp(24)).coerceAtLeast(dp(24)))
        }

        runCatching { windowManager.addView(panel, params) }
        panelView = panel
        panelParams = params
    }

    private fun createPanelView(): View {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(18).toFloat()
                setColor(Color.parseColor("#FF1B1F26"))
                setStroke(dp(1), Color.parseColor("#33FFFFFF"))
            }
            setPadding(dp(6), dp(10), dp(6), dp(6))
            elevation = dp(10).toFloat()
            minimumWidth = dp(196)
        }

        container.addView(TextView(this).apply {
            text = "AI 沟通助手"
            setTextColor(Color.parseColor("#99FFFFFF"))
            textSize = 12f
            setPadding(dp(12), 0, dp(12), dp(6))
        })

        container.addView(panelRow("#FF7EA6D8", "框选屏幕 · 出回复") {
            removePanel()
            launchCapture()
        })
        container.addView(panelRow("#FF8FBFA8", "上传图片") {
            removePanel()
            CaptureBus.sendCommand(COMMAND_PICK_IMAGE)
            openApp()
        })
        container.addView(panelRow("#FFD8C48F", "文字输入") {
            showTextInputOverlay()
        })
        container.addView(panelRow("#FFB6A0D8", "打开设置") {
            removePanel()
            CaptureBus.sendCommand(COMMAND_OPEN_SETTINGS)
            openApp()
        })

        container.addView(View(this).apply {
            setBackgroundColor(Color.parseColor("#22FFFFFF"))
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply {
            topMargin = dp(4)
            bottomMargin = dp(4)
            leftMargin = dp(10)
            rightMargin = dp(10)
        })

        container.addView(panelRow("#FF9A9A9A", "收起面板") {
            removePanel()
        })
        container.addView(panelRow("#FF9A9A9A", "隐藏悬浮球") {
            removePanel()
            hideByUser()
        })
        container.addView(panelRow("#FFD08A8A", "关闭悬浮球") {
            removePanel()
            scope.launch {
                runCatching { settings.setOverlayEnabled(false) }
                stopSelf()
            }
        })

        return container
    }

    private fun panelRow(dotColor: String, title: String, onClick: () -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = RippleDrawable(
                ColorStateList.valueOf(Color.parseColor("#22FFFFFF")),
                GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(10).toFloat()
                    setColor(Color.TRANSPARENT)
                },
                null
            )
            isClickable = true
            setOnClickListener { onClick() }
        }
        val dot = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor(dotColor))
            }
        }
        row.addView(dot, LinearLayout.LayoutParams(dp(7), dp(7)).apply { marginEnd = dp(10) })
        row.addView(TextView(this).apply {
            text = title
            setTextColor(Color.WHITE)
            textSize = 14f
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        return row
    }

    private fun removePanel() {
        panelView?.let { panel -> runCatching { windowManager.removeView(panel) } }
        panelView = null
        panelParams = null
    }

    // --------------------------------------------------------------- actions

    private fun launchCapture() {
        hiddenForCapture = true
        applyBallVisibility()
        awaitingCapture = true
        val intent = Intent(this, ScreenCaptureActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(ScreenCaptureActivity.EXTRA_MODE, ScreenCaptureActivity.MODE_OVERLAY)
        }
        runCatching { startActivity(intent) }.onFailure {
            hiddenForCapture = false
            awaitingCapture = false
            applyBallVisibility()
        }
        // Safety net: never leave the service stuck in "waiting for a crop".
        scope.launch {
            delay(CAPTURE_TIMEOUT_MS)
            if (awaitingCapture) {
                awaitingCapture = false
                toast("这次没有拿到截图结果")
            }
        }
    }

    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        }
        runCatching { startActivity(intent) }
    }

    private fun hideByUser() {
        hiddenByUser = true
        applyBallVisibility()
    }

    private fun showByUser() {
        hiddenByUser = false
        hiddenForCapture = false
        hiddenForResult = false
        applyBallVisibility()
    }

    private fun applyBallVisibility() {
        val visible = !hiddenForCapture && !hiddenByUser && !hiddenForResult
        ballView?.visibility = if (visible) View.VISIBLE else View.GONE
        if (!visible) removePanel()
    }

    private fun applyAlpha(alpha: Float) {
        ballAlpha = alpha
        ballView?.alpha = alpha
    }

    // ------------------------------------------------------- quick reply flow

    /**
     * capture -> crop -> local OCR -> persona-aware generation -> 3 tappable options.
     * Everything renders in the overlay so the user never leaves the chat app.
     */
    private fun startQuickFlow(bitmap: Bitmap) {
        flowJob?.cancel()
        flowJob = scope.launch {
            showStatus("正在本地识别截图…")
            val ocr = runCatching { container.ocrEngine.recognize(bitmap) }.getOrNull()
            val ocrText = (ocr as? NetResult.Success)?.value.orEmpty()

            // No text recognised -> let a vision-capable model look at the crop itself.
            val images = if (ocrText.isBlank()) {
                runCatching { listOf(ImagePayload(BitmapUtils.toJpegBase64(bitmap))) }
                    .getOrDefault(emptyList())
            } else {
                emptyList()
            }

            val prefs = runCatching { settings.snapshot() }.getOrNull()
            val persona = resolvePersona(prefs?.activePersonaId)
            lastQuickContext = ocrText
            lastQuickPersona = persona
            generateQuick(ocrText, persona, images)
        }
    }

    private suspend fun generateQuick(
        chatText: String,
        persona: Persona,
        images: List<ImagePayload>,
    ) {
        val activeBook = runCatching { container.personaBookRepository.active() }.getOrNull()
        val book = activeBook?.content.orEmpty()
        // 人设书优先级高于预设人设，提示里要如实写出来，避免让人以为只用了「自然」
        val label = if (activeBook != null && book.isNotBlank()) {
            "人设书「${activeBook.title}」· 预设「${persona.name}」"
        } else {
            "预设「${persona.name}」"
        }
        showStatus("正在按 $label 写回复…")
        val outcome = runCatching {
            container.copilotEngine.generate(
                GenerateInput(
                    chatContent = chatText,
                    intent = "",
                    persona = persona,
                    images = images,
                    ocrText = chatText,
                    personaBook = book,
                )
            )
        }.getOrNull()

        when (outcome) {
            null -> {
                hideStatus()
                showQuickMessage("生成失败", "出现未知错误，请重试。", "关闭") { closeQuickPanels() }
            }

            is NetResult.Failure -> {
                hideStatus()
                showQuickMessage("这次没成功", outcome.message, "关闭") { closeQuickPanels() }
            }

            is NetResult.Success -> {
                showQuickResults(outcome.value.result.replies.take(3), label)
            }
        }
    }

    private suspend fun resolvePersona(activeId: Long?): Persona {
        val personas = runCatching { container.personaRepository.personas.first() }
            .getOrDefault(emptyList())
        return personas.firstOrNull { it.id == activeId }
            ?: personas.firstOrNull { it.presetKey == "natural" }
            ?: personas.firstOrNull()
            ?: Persona(name = "默认")
    }

    private fun chooseReply(text: String) {
        runCatching {
            val clipboard = getSystemService(ClipboardManager::class.java)
            clipboard?.setPrimaryClip(ClipData.newPlainText("AI 回复", text))
        }
        val filled = ReplyFillService.isConnected && ReplyFillService.fill(text)
        closeQuickPanels()
        toast(if (filled) "已填入输入框，确认一下再发送" else "已复制，长按输入框粘贴")
    }

    private fun regenerateQuick() {
        // Without OCR text there is nothing to re-run against; ask for a fresh capture.
        if (lastQuickContext.isBlank()) {
            toast("这次没有识别到文字，请重新截屏")
            return
        }
        scope.launch {
            generateQuick(lastQuickContext, lastQuickPersona, emptyList())
        }
    }

    private fun closeQuickPanels() {
        hideStatus()
        hideQuickResults()
        closeTextInputOverlay()
        hiddenForCapture = false
        hiddenForResult = false
        applyBallVisibility()
    }

    // ------------------------------------------------------------- status UI

    private fun showStatus(message: String) {
        statusText?.let {
            it.text = message
            return
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(24).toFloat()
                setColor(Color.parseColor("#FF1B1F26"))
                setStroke(dp(1), Color.parseColor("#33FFFFFF"))
            }
            setPadding(dp(16), dp(10), dp(18), dp(10))
        }
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleSmall).apply {
            indeterminateTintList = ColorStateList.valueOf(Color.WHITE)
        }
        card.addView(bar, LinearLayout.LayoutParams(dp(18), dp(18)))
        val label = TextView(this).apply {
            text = message
            setTextColor(Color.WHITE)
            textSize = 13f
        }
        card.addView(
            label,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(10) }
        )
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(110)
        }
        runCatching { windowManager.addView(card, params) }
        statusView = card
        statusText = label
    }

    private fun hideStatus() {
        statusView?.let { runCatching { windowManager.removeView(it) } }
        statusView = null
        statusText = null
    }

    // ------------------------------------------------------------- result UI

    private fun showQuickResults(replies: List<ReplySuggestion>, personaLabel: String) {
        hideStatus()
        hideQuickResults()
        if (replies.isEmpty()) {
            showQuickMessage("没有生成结果", "模型这次没有返回可用的回复。", "关闭") {
                closeQuickPanels()
            }
            return
        }

        hiddenForResult = true
        applyBallVisibility()

        val screen = screenSize()
        val cardWidth = min(screen.x - dp(32), dp(440)).coerceAtLeast(dp(260))

        val card = quickCard()

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(2), dp(2), dp(6))
        }
        header.addView(
            TextView(this).apply {
                text = "⠿"
                setTextColor(Color.parseColor("#88FFFFFF"))
                textSize = 14f
                setPadding(dp(6), dp(2), dp(8), dp(2))
            }
        )
        header.addView(
            TextView(this).apply {
                text = "选一条直接填入 · ${personaLabel}"
                setTextColor(Color.parseColor("#CCFFFFFF"))
                textSize = 12f
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        header.addView(
            TextView(this).apply {
                text = "收起"
                setTextColor(Color.parseColor("#FF8FBFA8"))
                textSize = 12f
                setPadding(dp(10), dp(6), dp(8), dp(6))
                isClickable = true
                setOnClickListener { collapseToBall() }
            }
        )
        header.addView(
            TextView(this).apply {
                text = "关闭"
                setTextColor(Color.parseColor("#FF9FC0EA"))
                textSize = 12f
                setPadding(dp(8), dp(6), dp(4), dp(6))
                isClickable = true
                setOnClickListener { closeQuickPanels() }
            }
        )
        card.addView(header)

        replies.forEachIndexed { index, reply ->
            val row = quickReplyRow(index, reply) { chooseReply(reply.text) }
            card.addView(
                row,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(8) }
            )
        }

        // 透明度 / 尺寸控制
        val tools = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        tools.addView(quickTool("透明−") { adjustResultAlpha(-0.12f) })
        tools.addView(quickTool("透明+") { adjustResultAlpha(0.12f) })
        tools.addView(quickTool("缩小") { adjustResultScale(-0.1f) })
        tools.addView(quickTool("放大") { adjustResultScale(0.1f) })
        card.addView(tools)

        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(6), 0, 0)
        }
        footer.addView(
            TextView(this).apply {
                text = if (ReplyFillService.isConnected) "点一条会自动填进输入框" else "点一条会复制到剪贴板"
                setTextColor(Color.parseColor("#99FFFFFF"))
                textSize = 11f
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        footer.addView(
            TextView(this).apply {
                text = "换一批"
                setTextColor(Color.parseColor("#FF9FC0EA"))
                textSize = 12f
                setPadding(dp(10), dp(4), dp(4), dp(4))
                isClickable = true
                setOnClickListener { regenerateQuick() }
            }
        )
        card.addView(footer)

        val widthPx = (cardWidth * resultScale).toInt().coerceAtMost(screen.x - dp(16))
        val params = WindowManager.LayoutParams(
            widthPx,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = ((screen.x - widthPx) / 2).coerceAtLeast(0)
            y = (screen.y * 0.52f).toInt()
        }
        runCatching { windowManager.addView(card, params) }
        card.alpha = resultAlpha
        attachResultDrag(header, card, params)
        resultView = card
        resultParams = params
    }

    private fun showQuickMessage(
        title: String,
        message: String,
        actionLabel: String,
        onAction: () -> Unit,
    ) {
        hideQuickResults()
        hiddenForResult = true
        applyBallVisibility()

        val screen = screenSize()
        val cardWidth = min(screen.x - dp(32), dp(440)).coerceAtLeast(dp(260))
        val card = quickCard()

        card.addView(TextView(this).apply {
            text = title
            setTextColor(Color.WHITE)
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
        })
        card.addView(TextView(this).apply {
            text = message
            setTextColor(Color.parseColor("#CCFFFFFF"))
            textSize = 12f
            setPadding(0, dp(6), 0, dp(4))
        })
        card.addView(TextView(this).apply {
            text = actionLabel
            setTextColor(Color.parseColor("#FF9FC0EA"))
            textSize = 13f
            gravity = Gravity.END
            setPadding(dp(8), dp(8), dp(4), 0)
            isClickable = true
            setOnClickListener { onAction() }
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        val params = WindowManager.LayoutParams(
            cardWidth,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(140)
        }
        runCatching { windowManager.addView(card, params) }
        resultView = card
    }

    private fun quickTool(label: String, onClick: () -> Unit): View =
        TextView(this).apply {
            text = label
            setTextColor(Color.parseColor("#FFB8D4F0"))
            textSize = 12f
            setPadding(dp(9), dp(6), dp(9), dp(6))
            isClickable = true
            setOnClickListener { onClick() }
        }

    /** 结果面板：透明度加减，立即生效并持久化。 */
    private fun adjustResultAlpha(delta: Float) {
        resultAlpha = (resultAlpha + delta).coerceIn(0.35f, 1f)
        resultView?.alpha = resultAlpha
        scope.launch { runCatching { settings.setOverlayResultAlpha(resultAlpha) } }
    }

    /** 结果面板：缩放加减，重建窗口宽度。 */
    private fun adjustResultScale(delta: Float) {
        resultScale = (resultScale + delta).coerceIn(0.80f, 1.40f)
        scope.launch { runCatching { settings.setOverlayResultScale(resultScale) } }
        val view = resultView ?: return
        val params = resultParams ?: return
        val screen = screenSize()
        val baseW = min(screen.x - dp(32), dp(440)).coerceAtLeast(dp(260))
        params.width = (baseW * resultScale).toInt().coerceAtMost(screen.x - dp(16))
        params.x = ((screen.x - params.width) / 2).coerceAtLeast(0)
        runCatching { windowManager.updateViewLayout(view, params) }
    }

    /** 收起：把面板缩回悬浮球，而不是只能隐藏或关闭。 */
    private fun collapseToBall() {
        hideQuickResults()
        hiddenForResult = false
        applyBallVisibility()
    }

    /** 按住标题栏拖动结果面板。 */
    private fun attachResultDrag(handle: View, target: View, params: WindowManager.LayoutParams) {
        var startX = 0
        var startY = 0
        var downRawX = 0f
        var downRawY = 0f
        handle.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    downRawX = event.rawX
                    downRawY = event.rawY
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val screen = screenSize()
                    params.x = (startX + (event.rawX - downRawX).toInt())
                        .coerceIn(0, (screen.x - params.width).coerceAtLeast(0))
                    params.y = (startY + (event.rawY - downRawY).toInt())
                        .coerceIn(0, (screen.y - dp(80)).coerceAtLeast(0))
                    runCatching { windowManager.updateViewLayout(target, params) }
                    true
                }

                else -> false
            }
        }
    }

    private fun copyText(text: String) {
        runCatching {
            val cm = getSystemService(ClipboardManager::class.java)
            cm.setPrimaryClip(ClipData.newPlainText("AI 沟通助手", text))
        }
    }

    /**
     * 透明悬浮窗里的文字输入 —— 不用切回 App。
     * 可直接输入 / 粘贴，然后一键复制或填入聊天输入框。
     */
    private fun showTextInputOverlay() {
        removePanel()
        hideQuickResults()
        if (textInputView != null) return
        hiddenForResult = true
        applyBallVisibility()

        val screen = screenSize()
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(20).toFloat()
                setColor(Color.parseColor("#F21B1F26"))
                setStroke(dp(1), Color.parseColor("#33FFFFFF"))
            }
            setPadding(dp(14), dp(12), dp(14), dp(10))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(
            TextView(this).apply {
                text = "直接在这里输入 · 不用切回应用"
                setTextColor(Color.parseColor("#CCFFFFFF"))
                textSize = 12f
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        header.addView(quickTool("关闭") { closeTextInputOverlay() })
        wrap.addView(header)

        val input = android.widget.EditText(this).apply {
            hint = "输入要说的话，或粘贴已经想好的回复"
            setHintTextColor(Color.parseColor("#66FFFFFF"))
            setTextColor(Color.WHITE)
            textSize = 15f
            setBackgroundColor(Color.TRANSPARENT)
            maxLines = 6
            isFocusableInTouchMode = true
        }
        wrap.addView(
            input,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(96))
        )

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, 0)
        }
        actions.addView(quickTool("复制") {
            val text = input.text?.toString().orEmpty()
            if (text.isNotBlank()) {
                copyText(text)
                toast("已复制到剪贴板")
            }
        })
        actions.addView(quickTool("填入聊天框") {
            val text = input.text?.toString().orEmpty()
            if (text.isNotBlank()) {
                if (ReplyFillService.fill(text)) toast("已填入输入框") else {
                    copyText(text)
                    toast("没找到输入框，已复制")
                }
            }
        })
        wrap.addView(actions)

        val width = min(screen.x - dp(32), dp(460)).coerceAtLeast(dp(280))
        val params = WindowManager.LayoutParams(
            width,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = ((screen.x - width) / 2).coerceAtLeast(0)
            y = (screen.y * 0.40f).toInt()
            softInputMode =
                WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
        runCatching { windowManager.addView(wrap, params) }
        textInputView = wrap
        textInputParams = params
        input.requestFocus()
    }

    private fun closeTextInputOverlay() {
        textInputView?.let { runCatching { windowManager.removeView(it) } }
        textInputView = null
        textInputParams = null
        hiddenForResult = false
        applyBallVisibility()
    }

    private fun quickCard(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            cornerRadius = dp(20).toFloat()
            setColor(Color.parseColor("#FF1B1F26"))
            setStroke(dp(1), Color.parseColor("#33FFFFFF"))
        }
        setPadding(dp(14), dp(14), dp(14), dp(10))
        elevation = dp(12).toFloat()
    }

    private fun quickReplyRow(index: Int, reply: ReplySuggestion, onClick: () -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = RippleDrawable(
                ColorStateList.valueOf(Color.parseColor("#22FFFFFF")),
                GradientDrawable().apply {
                    cornerRadius = dp(12).toFloat()
                    setColor(Color.parseColor("#14FFFFFF"))
                },
                null
            )
            setPadding(dp(12), dp(10), dp(12), dp(10))
            isClickable = true
            setOnClickListener { onClick() }
        }
        row.addView(TextView(this).apply {
            text = "${index + 1} · ${reply.label}"
            setTextColor(Color.parseColor("#FF9FC0EA"))
            textSize = 11f
        })
        row.addView(TextView(this).apply {
            text = reply.text
            setTextColor(Color.WHITE)
            textSize = 15f
            maxLines = 4
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(4), 0, 0)
        })
        return row
    }

    private fun hideQuickResults() {
        resultView?.let { runCatching { windowManager.removeView(it) } }
        resultView = null
        resultParams = null
    }

    private fun toast(message: String) {
        runCatching { Toast.makeText(this, message, Toast.LENGTH_SHORT).show() }
    }

    // ------------------------------------------------------------ utilities

    private fun screenSize(): Point {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            Point(bounds.width(), bounds.height())
        } else {
            val metrics = resources.displayMetrics
            Point(metrics.widthPixels, metrics.heightPixels)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun startForegroundSafely() {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        runCatching {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type)
        }.onFailure {
            runCatching { startForeground(NOTIFICATION_ID, buildNotification()) }
        }
    }

    private fun buildNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notif_overlay_title))
            .setContentText(getString(R.string.notif_overlay_text))
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(contentIntent)
            .build()
    }

    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.channel_overlay),
                    NotificationManager.IMPORTANCE_LOW
                ).apply { setShowBadge(false) }
            )
        }
    }

    companion object {
        const val CHANNEL_ID = "ai_copilot_overlay"
        private const val NOTIFICATION_ID = 0x5C13
        private const val BALL_SIZE_DP = 54
        private const val CAPTURE_TIMEOUT_MS = 90_000L
        const val COMMAND_PICK_IMAGE = "pick_image"
        const val COMMAND_TEXT_INPUT = "text_input"
        const val COMMAND_OPEN_SETTINGS = "open_settings"

        @Volatile
        private var instance: OverlayService? = null

        fun isRunning(): Boolean = instance != null

        fun start(context: android.content.Context) {
            val intent = Intent(context, OverlayService::class.java)
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: android.content.Context) {
            context.stopService(Intent(context, OverlayService::class.java))
        }

        /** Used by the capture activity so the ball never appears in a screenshot. */
        fun setBallVisible(visible: Boolean) {
            val service = instance ?: return
            service.hiddenForCapture = !visible
            service.applyBallVisibility()
        }

        fun showBall() {
            instance?.showByUser()
        }

        fun hideBall() {
            instance?.hideByUser()
        }

        fun setAlpha(alpha: Float) {
            instance?.applyAlpha(alpha)
        }
    }
}
