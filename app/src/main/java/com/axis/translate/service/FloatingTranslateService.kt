package com.axis.translate.service

import android.annotation.SuppressLint
import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.IBinder
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
import com.axis.translate.AxisApp
import com.axis.translate.di.AppContainer
import com.axis.translate.domain.model.HistoryItem
import com.axis.translate.domain.model.InputType
import com.axis.translate.domain.model.Language
import com.axis.translate.domain.model.TranslationRequest
import com.axis.translate.domain.model.TranslationResult
import com.axis.translate.util.AndroidUtils
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Floating translation bubble (SPEC #22): a short-lived overlay service —
 * deliberately NOT foreground. Tapping the bubble reads the clipboard
 * (only on that explicit user action) and shows a mini overlay panel with
 * the offline translation, plus Copy / Close actions.
 */
class FloatingTranslateService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var container: AppContainer
    private lateinit var scope: CoroutineScope

    private var bubbleView: View? = null
    private var panelView: View? = null
    private var translationJob: Job? = null

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WindowManager::class.java)
        container = (application as AxisApp).container
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (bubbleView == null) {
            addBubble()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        bubbleView?.let { bubble -> runCatching { windowManager.removeView(bubble) } }
        bubbleView = null
        removePanel()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("ClickableViewAccessibility")
    private fun addBubble() {
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = dp(BUBBLE_INITIAL_OFFSET_DP)
        }

        val bubble = FrameLayout(this).apply {
            minimumWidth = dp(BUBBLE_SIZE_DP)
            minimumHeight = dp(BUBBLE_SIZE_DP)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor(BUBBLE_COLOR_ARGB))
            }
            addView(
                TextView(this@FloatingTranslateService).apply {
                    text = BUBBLE_LABEL
                    contentDescription = "Translate clipboard"
                    setTextColor(Color.WHITE)
                    textSize = 15f
                    typeface = Typeface.DEFAULT_BOLD
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        Gravity.CENTER
                    )
                }
            )
        }

        var downParamsX = 0
        var downParamsY = 0
        var downRawX = 0f
        var downRawY = 0f
        var draggedBeyondSlop = false
        val tapSlopPx = dp(TAP_SLOP_DP)

        bubble.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downParamsX = params.x
                    downParamsY = params.y
                    downRawX = event.rawX
                    downRawY = event.rawY
                    draggedBeyondSlop = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (abs(dx) > tapSlopPx || abs(dy) > tapSlopPx) {
                        draggedBeyondSlop = true
                    }
                    if (draggedBeyondSlop) {
                        params.x = downParamsX + dx.toInt()
                        params.y = downParamsY + dy.toInt()
                        runCatching { windowManager.updateViewLayout(bubble, params) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!draggedBeyondSlop) {
                        translateClipboard()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }

        try {
            windowManager.addView(bubble, params)
            bubbleView = bubble
        } catch (_: Exception) {
            // Overlay permission was revoked under us — nothing to show.
            stopSelf()
        }
    }

    private fun translateClipboard() {
        // The clipboard is read ONLY on this explicit user action (SPEC #22).
        val text = AndroidUtils.clipboardText(this)
        if (text.isNullOrBlank()) {
            Toast.makeText(this, "Clipboard is empty", Toast.LENGTH_SHORT).show()
            return
        }

        translationJob?.cancel()
        removePanel()

        val panel = MiniPanel()
        try {
            windowManager.addView(panel.view, panel.params)
        } catch (_: Exception) {
            return
        }
        panelView = panel.view

        translationJob = scope.launch {
            val settings = container.settingsRepository.current()
            val target = Language.byCode(settings.targetLanguageCode)
                ?: Language.FALLBACK_CATALOG.first()
            try {
                val result = container.translationManager.translate(
                    TranslationRequest(
                        text = text,
                        source = Language.AUTO,
                        target = target,
                        inputType = InputType.CLIPBOARD
                    )
                )
                recordHistory(text, result, target)
                panel.showResult(result.translatedText)
            } catch (ce: CancellationException) {
                throw ce
            } catch (error: Exception) {
                panel.showError(error.message ?: "Translation failed")
            }
        }
    }

    private suspend fun recordHistory(sourceText: String, result: TranslationResult, target: Language) {
        // Best-effort history logging — never blocks the result panel.
        try {
            container.historyRepository.add(
                HistoryItem(
                    sourceCode = result.detectedLanguage?.code ?: Language.AUTO_CODE,
                    targetCode = target.code,
                    sourceText = sourceText,
                    translatedText = result.translatedText,
                    inputType = InputType.CLIPBOARD,
                    durationMs = result.durationMs,
                    detectedLanguageCode = result.detectedLanguage?.code
                )
            )
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Exception) {
            // Ignored deliberately.
        }
    }

    private fun removePanel() {
        panelView?.let { view ->
            runCatching { windowManager.removeView(view) }
        }
        panelView = null
    }

    /**
     * The mini overlay result panel: indeterminate progress while
     * translating, then the translated text (max 6 lines) with Copy / Close
     * actions, or an error message.
     */
    private inner class MiniPanel {

        val view: LinearLayout = LinearLayout(this@FloatingTranslateService).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(20).toFloat()
            }
        }

        val params = WindowManager.LayoutParams(
            (resources.displayMetrics.widthPixels * PANEL_WIDTH_FRACTION).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }

        private val progressBar = ProgressBar(this@FloatingTranslateService).apply {
            isIndeterminate = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(8)
            }
        }

        private val resultView = TextView(this@FloatingTranslateService).apply {
            setTextColor(COLOR_TEXT)
            textSize = 15f
            maxLines = 6
            ellipsize = TextUtils.TruncateAt.END
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        }

        private val errorView = TextView(this@FloatingTranslateService).apply {
            setTextColor(COLOR_ERROR)
            textSize = 14f
            maxLines = 4
            ellipsize = TextUtils.TruncateAt.END
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        }

        private val copyButton = actionButton("Copy")
        private val closeButton = actionButton("Close")

        private val buttonsRow = LinearLayout(this@FloatingTranslateService).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) }
        }

        private var resultText: String? = null

        init {
            (closeButton.layoutParams as LinearLayout.LayoutParams).leftMargin = dp(8)
            buttonsRow.addView(copyButton)
            buttonsRow.addView(closeButton)

            view.addView(
                TextView(this@FloatingTranslateService).apply {
                    text = "Axis Translate"
                    setTextColor(Color.parseColor(BUBBLE_COLOR_OPAQUE))
                    textSize = 14f
                    typeface = Typeface.DEFAULT_BOLD
                }
            )
            view.addView(progressBar)
            view.addView(resultView)
            view.addView(errorView)
            view.addView(buttonsRow)

            closeButton.setOnClickListener { removePanel() }
            copyButton.setOnClickListener {
                resultText?.takeIf { it.isNotBlank() }?.let { text ->
                    AndroidUtils.copyToClipboard(this@FloatingTranslateService, text)
                    Toast.makeText(this@FloatingTranslateService, "Copied", Toast.LENGTH_SHORT).show()
                }
            }
        }

        fun showResult(translated: String) {
            if (!view.isAttachedToWindow) return
            resultText = translated
            progressBar.visibility = View.GONE
            resultView.text = translated
            resultView.visibility = View.VISIBLE
            errorView.visibility = View.GONE
            copyButton.visibility = View.VISIBLE
            buttonsRow.visibility = View.VISIBLE
        }

        fun showError(message: String) {
            if (!view.isAttachedToWindow) return
            progressBar.visibility = View.GONE
            errorView.text = message
            errorView.visibility = View.VISIBLE
            resultView.visibility = View.GONE
            copyButton.visibility = View.GONE
            buttonsRow.visibility = View.VISIBLE
        }

        private fun actionButton(label: String): TextView = TextView(this@FloatingTranslateService).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            isClickable = true
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = GradientDrawable().apply {
                setColor(Color.parseColor(BUBBLE_COLOR_OPAQUE))
                cornerRadius = dp(16).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
    }

    companion object {
        private const val BUBBLE_SIZE_DP = 56
        private const val BUBBLE_INITIAL_OFFSET_DP = 120
        private const val BUBBLE_LABEL = "AX"
        private const val BUBBLE_COLOR_ARGB = "#EE4F46E5"
        private const val BUBBLE_COLOR_OPAQUE = "#FF4F46E5"
        private const val PANEL_WIDTH_FRACTION = 0.7f
        private const val TAP_SLOP_DP = 8
        private val COLOR_TEXT = Color.parseColor("#FF111827")
        private val COLOR_ERROR = Color.parseColor("#FFDC2626")

        /** Shows the floating bubble (no-op when overlay permission is missing). */
        fun start(context: Context) {
            if (!Settings.canDrawOverlays(context)) return
            runCatching {
                context.startService(Intent(context, FloatingTranslateService::class.java))
            }
        }

        /** Removes the bubble and any open panel. */
        fun stop(context: Context) {
            runCatching {
                context.stopService(Intent(context, FloatingTranslateService::class.java))
            }
        }
    }
}
