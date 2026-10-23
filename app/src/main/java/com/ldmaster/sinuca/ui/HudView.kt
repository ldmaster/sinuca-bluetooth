package com.ldmaster.sinuca.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

class HudView(
    context: Context,
    private val provider: () -> HudState,
    private val actions: Actions
) : View(context) {

    interface Actions {
        fun onAimDelta(deltaPixels: Float)
        fun onShoot()
        fun onExit()
        fun onCamera()
    }

    var power: Float = 0.55f
        private set

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.LEFT
    }

    private val shootRect = RectF()
    private val exitRect = RectF()
    private val cameraRect = RectF()
    private val powerRect = RectF()

    private var gesture = GESTURE_NONE
    private var lastTouchX = 0f

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val state = provider()
        val w = width.toFloat()
        val h = height.toFloat()

        drawTopBar(canvas, state, w)
        if (!state.started) {
            drawCenterOverlay(canvas, w, h, "Conectando ao host…")
        } else if (state.winner >= 0) {
            drawWinner(canvas, state, w, h)
        } else {
            drawControls(canvas, state, w, h)
        }
        postInvalidateOnAnimation()
    }

    private fun drawTopBar(canvas: Canvas, state: HudState, w: Float) {
        val top = dp(8f)
        val bottom = dp(92f)
        fill.color = Color.parseColor("#D90B1220")
        canvas.drawRoundRect(dp(12f), top, w - dp(12f), bottom, dp(12f), dp(12f), fill)
        stroke.color = Color.parseColor("#33506A88")
        canvas.drawRoundRect(dp(12f), top, w - dp(12f), bottom, dp(12f), dp(12f), stroke)

        val practice = state.practice
        val localPotted: List<Int>
        val opponentPotted: List<Int>
        val localLabel: String
        val opponentLabel: String
        if (practice) {
            localPotted = state.potted0
            opponentPotted = state.potted1
            localLabel = "J1"
            opponentLabel = "J2"
        } else if (state.localPlayer == 1) {
            localPotted = state.potted1
            opponentPotted = state.potted0
            localLabel = "VOCÊ"
            opponentLabel = "ADV"
        } else {
            localPotted = state.potted0
            opponentPotted = state.potted1
            localLabel = "VOCÊ"
            opponentLabel = "ADV"
        }
        drawPottedRow(canvas, localLabel, localPotted, dp(34f), "#7FD1A6")
        drawPottedRow(canvas, opponentLabel, opponentPotted, dp(66f), "#9AA8B8")

        textPaint.textAlign = Paint.Align.CENTER
        textPaint.textSize = dp(14f)
        textPaint.color = Color.parseColor("#F2F6FA")
        val status = when {
            state.moving -> "BOLAS EM MOVIMENTO"
            state.practice -> "VEZ DO JOGADOR ${state.turn + 1}"
            state.canShoot -> "SUA VEZ"
            else -> "VEZ DO ADVERSÁRIO"
        }
        canvas.drawText(status, w / 2f, dp(44f), textPaint)

        cameraRect.set(w - dp(204f), dp(16f), w - dp(112f), dp(64f))
        fill.color = Color.parseColor("#1D3B5C")
        canvas.drawRoundRect(cameraRect, dp(9f), dp(9f), fill)
        textPaint.textSize = dp(11f)
        textPaint.color = Color.parseColor("#D6E6F5")
        canvas.drawText("VISÃO ${state.cameraMode + 1}", cameraRect.centerX(), cameraRect.centerY() + dp(4f), textPaint)

        exitRect.set(w - dp(104f), dp(16f), w - dp(20f), dp(64f))
        fill.color = Color.parseColor("#262F3D")
        canvas.drawRoundRect(exitRect, dp(9f), dp(9f), fill)
        textPaint.textSize = dp(11f)
        textPaint.color = Color.parseColor("#C7D2DE")
        canvas.drawText("SAIR", exitRect.centerX(), exitRect.centerY() + dp(4f), textPaint)
    }

    private fun drawPottedRow(
        canvas: Canvas,
        label: String,
        potted: List<Int>,
        centerY: Float,
        labelColor: String
    ) {
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.textSize = dp(11f)
        textPaint.color = Color.parseColor(labelColor)
        canvas.drawText(label, dp(26f), centerY + dp(4f), textPaint)

        val radius = dp(8f)
        val spacing = dp(20f)
        var x = dp(82f) + radius
        for (number in potted) {
            fill.color = ballColor(number)
            canvas.drawCircle(x, centerY, radius, fill)
            if (number in 9..15) {
                fill.color = Color.parseColor("#F2F6FA")
                canvas.drawRoundRect(
                    x - radius * 0.8f,
                    centerY - dp(3f),
                    x + radius * 0.8f,
                    centerY + dp(3f),
                    dp(2f),
                    dp(2f),
                    fill
                )
            }
            textPaint.textAlign = Paint.Align.CENTER
            textPaint.textSize = dp(7.5f)
            textPaint.color = if (number == 1 || number == 5 || number == 0) {
                Color.parseColor("#101820")
            } else {
                Color.WHITE
            }
            canvas.drawText(number.toString(), x, centerY + dp(2.7f), textPaint)
            x += spacing
        }
    }

    private fun drawControls(canvas: Canvas, state: HudState, w: Float, h: Float) {
        val panelTop = h - dp(92f)
        fill.color = Color.parseColor("#B30B1220")
        canvas.drawRoundRect(dp(12f), panelTop, w - dp(12f), h - dp(10f), dp(14f), dp(14f), fill)

        textPaint.textAlign = Paint.Align.LEFT
        textPaint.textSize = dp(12f)
        textPaint.color = Color.parseColor("#8FA0B4")
        canvas.drawText("Arraste na mesa para mirar", dp(26f), h - dp(45f), textPaint)
        textPaint.textSize = dp(11f)
        textPaint.color = Color.parseColor("#66758A")
        canvas.drawText("Força: arraste a barra à direita", dp(26f), h - dp(27f), textPaint)

        powerRect.set(w - dp(86f), dp(128f), w - dp(58f), h - dp(116f))
        fill.color = Color.parseColor("#1A2430")
        canvas.drawRoundRect(powerRect, dp(12f), dp(12f), fill)

        val clamped = power.coerceIn(0f, 1f)
        val level = RectF(powerRect)
        level.top = powerRect.bottom - powerRect.height() * clamped
        if (level.height() > dp(2f)) {
            val gradient = LinearGradient(
                0f, powerRect.bottom, 0f, powerRect.top,
                intArrayOf(
                    Color.parseColor("#2ECC71"),
                    Color.parseColor("#F1C40F"),
                    Color.parseColor("#E74C3C")
                ),
                null,
                Shader.TileMode.CLAMP
            )
            fill.shader = gradient
            canvas.drawRoundRect(level, dp(12f), dp(12f), fill)
            fill.shader = null
        }
        fill.color = Color.WHITE
        canvas.drawCircle(powerRect.centerX(), level.top.coerceIn(powerRect.top + dp(4f), powerRect.bottom - dp(4f)), dp(4.5f), fill)

        textPaint.textAlign = Paint.Align.CENTER
        textPaint.textSize = dp(11f)
        textPaint.color = Color.parseColor("#C7D2DE")
        canvas.drawText("${(clamped * 100).toInt()}%", powerRect.centerX(), powerRect.top - dp(8f), textPaint)

        if (state.message.isNotEmpty()) {
            textPaint.textAlign = Paint.Align.CENTER
            textPaint.textSize = dp(12f)
            val messageWidth = textPaint.measureText(state.message)
            fill.color = Color.parseColor("#B30B1220")
            canvas.drawRoundRect(
                w / 2f - messageWidth / 2f - dp(10f),
                dp(102f),
                w / 2f + messageWidth / 2f + dp(10f),
                dp(126f),
                dp(9f),
                dp(9f),
                fill
            )
            textPaint.color = Color.parseColor("#C7D2DE")
            canvas.drawText(state.message, w / 2f, dp(119f), textPaint)
        }

        shootRect.set(w * 0.50f, h - dp(78f), w - dp(140f), h - dp(22f))
        fill.color = if (state.canShoot) {
            Color.parseColor("#2ECC71")
        } else {
            Color.parseColor("#2A3542")
        }
        canvas.drawRoundRect(shootRect, dp(14f), dp(14f), fill)
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.textSize = dp(17f)
        textPaint.color = if (state.canShoot) Color.parseColor("#062015") else Color.parseColor("#708196")
        canvas.drawText("ATIRAR", shootRect.centerX(), shootRect.centerY() + dp(6f), textPaint)
    }

    private fun drawCenterOverlay(canvas: Canvas, w: Float, h: Float, message: String) {
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.textSize = dp(16f)
        textPaint.color = Color.parseColor("#F2F6FA")
        canvas.drawText(message, w / 2f, h / 2f, textPaint)
    }

    private fun drawWinner(canvas: Canvas, state: HudState, w: Float, h: Float) {
        fill.color = Color.parseColor("#B3000000")
        canvas.drawRect(0f, 0f, w, h, fill)
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.textSize = dp(26f)
        textPaint.color = Color.parseColor("#E8B21A")
        canvas.drawText("FIM DE JOGO", w / 2f, h / 2f - dp(18f), textPaint)
        textPaint.textSize = dp(16f)
        textPaint.color = Color.parseColor("#F2F6FA")
        canvas.drawText(state.message, w / 2f, h / 2f + dp(22f), textPaint)
        textPaint.textSize = dp(14f)
        textPaint.color = Color.parseColor("#9AA8B8")
        canvas.drawText("Toque para voltar ao menu", w / 2f, h / 2f + dp(62f), textPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val state = provider()
        val w = width.toFloat()
        val h = height.toFloat()

        if (state.winner >= 0) {
            if (event.actionMasked == MotionEvent.ACTION_UP) actions.onExit()
            return true
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (inside(exitRect, event.x, event.y)) {
                    actions.onExit()
                    return true
                }
                if (inside(cameraRect, event.x, event.y)) {
                    actions.onCamera()
                    return true
                }
                if (!state.started) return true
                if (inside(shootRect, event.x, event.y)) {
                    if (state.canShoot) actions.onShoot()
                    return true
                }
                val panelTop = h - dp(92f)
                if (event.x >= w - dp(130f) && event.y < panelTop && event.y > dp(96f)) {
                    gesture = GESTURE_POWER
                    updatePower(event.y)
                } else if (event.y < panelTop && event.y > dp(96f)) {
                    gesture = GESTURE_AIM
                    lastTouchX = event.x
                } else {
                    gesture = GESTURE_NONE
                }
            }
            MotionEvent.ACTION_MOVE -> {
                when (gesture) {
                    GESTURE_AIM -> {
                        val delta = event.x - lastTouchX
                        lastTouchX = event.x
                        if (abs(delta) > 0.01f) actions.onAimDelta(delta)
                    }
                    GESTURE_POWER -> updatePower(event.y)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                gesture = GESTURE_NONE
            }
        }
        return true
    }

    private fun updatePower(y: Float) {
        val height = powerRect.height()
        if (height <= 0f) return
        power = ((powerRect.bottom - y) / height).coerceIn(0f, 1f)
        invalidate()
    }

    private fun inside(rect: RectF, x: Float, y: Float): Boolean =
        x >= rect.left && x <= rect.right && y >= rect.top && y <= rect.bottom

    private fun ballColor(number: Int): Int = when (number) {
        0 -> Color.parseColor("#F7F7F2")
        1, 9 -> Color.parseColor("#E8B21A")
        2, 10 -> Color.parseColor("#1E4FA8")
        3, 11 -> Color.parseColor("#C0281E")
        4, 12 -> Color.parseColor("#5A2A7A")
        5, 13 -> Color.parseColor("#E06A12")
        6, 14 -> Color.parseColor("#0F6B34")
        7, 15 -> Color.parseColor("#7A1C1C")
        else -> Color.parseColor("#141414")
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    companion object {
        private const val GESTURE_NONE = 0
        private const val GESTURE_AIM = 1
        private const val GESTURE_POWER = 2
    }
}
