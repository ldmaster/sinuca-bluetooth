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

class HudView(
    context: Context,
    private val provider: () -> HudState,
    private val actions: Actions
) : View(context) {

    interface Actions {
        fun onAim(x: Float, y: Float)
        fun onShoot()
        fun onExit()
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
    private val powerRect = RectF()

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
        val bottom = dp(70f)
        fill.color = Color.parseColor("#D90B1220")
        canvas.drawRoundRect(dp(12f), top, w - dp(12f), bottom, dp(12f), dp(12f), fill)
        stroke.color = Color.parseColor("#33506A88")
        canvas.drawRoundRect(dp(12f), top, w - dp(12f), bottom, dp(12f), dp(12f), stroke)

        textPaint.textSize = dp(13f)
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.color = Color.parseColor("#7FD1A6")
        val mine = when {
            state.practice -> "J1 ${state.remaining0} · J2 ${state.remaining1}"
            state.localPlayer == 1 -> "VOCÊ ${state.remaining1} · ADV ${state.remaining0}"
            else -> "VOCÊ ${state.remaining0} · ADV ${state.remaining1}"
        }
        canvas.drawText(mine, dp(28f), dp(44f), textPaint)

        textPaint.textAlign = Paint.Align.CENTER
        textPaint.textSize = dp(15f)
        textPaint.color = Color.parseColor("#F2F6FA")
        val status = when {
            state.moving -> "BOLAS EM MOVIMENTO"
            state.practice -> "VEZ DO JOGADOR ${state.turn + 1}"
            state.canShoot -> "SUA VEZ"
            else -> "VEZ DO ADVERSÁRIO"
        }
        canvas.drawText(status, w / 2f, dp(44f), textPaint)

        exitRect.set(w - dp(96f), dp(16f), w - dp(20f), dp(62f))
        fill.color = Color.parseColor("#262F3D")
        canvas.drawRoundRect(exitRect, dp(9f), dp(9f), fill)
        textPaint.textSize = dp(13f)
        textPaint.color = Color.parseColor("#C7D2DE")
        canvas.drawText("SAIR", exitRect.centerX(), exitRect.centerY() + dp(5f), textPaint)
    }

    private fun drawControls(canvas: Canvas, state: HudState, w: Float, h: Float) {
        val panelTop = h - dp(100f)
        fill.color = Color.parseColor("#B30B1220")
        canvas.drawRoundRect(dp(12f), panelTop, w - dp(12f), h - dp(10f), dp(14f), dp(14f), fill)

        powerRect.set(dp(26f), panelTop + dp(40f), w * 0.62f, panelTop + dp(63f))
        fill.color = Color.parseColor("#1A2430")
        canvas.drawRoundRect(powerRect, dp(10f), dp(10f), fill)

        val clamped = power.coerceIn(0f, 1f)
        val level = RectF(powerRect)
        level.right = powerRect.left + powerRect.width() * clamped
        if (level.width() > dp(2f)) {
            val gradient = LinearGradient(
                powerRect.left, 0f, powerRect.right, 0f,
                intArrayOf(
                    Color.parseColor("#2ECC71"),
                    Color.parseColor("#F1C40F"),
                    Color.parseColor("#E74C3C")
                ),
                null,
                Shader.TileMode.CLAMP
            )
            fill.shader = gradient
            canvas.drawRoundRect(level, dp(10f), dp(10f), fill)
            fill.shader = null
        }

        textPaint.textAlign = Paint.Align.LEFT
        textPaint.textSize = dp(12f)
        textPaint.color = Color.parseColor("#9AA8B8")
        canvas.drawText("FORÇA ${(clamped * 100).toInt()}%", dp(26f), panelTop + dp(28f), textPaint)

        if (state.message.isNotEmpty()) {
            textPaint.textAlign = Paint.Align.CENTER
            textPaint.textSize = dp(12f)
            val messageWidth = textPaint.measureText(state.message)
            fill.color = Color.parseColor("#B30B1220")
            canvas.drawRoundRect(
                w / 2f - messageWidth / 2f - dp(10f),
                dp(78f),
                w / 2f + messageWidth / 2f + dp(10f),
                dp(102f),
                dp(9f),
                dp(9f),
                fill
            )
            textPaint.color = Color.parseColor("#C7D2DE")
            canvas.drawText(state.message, w / 2f, dp(95f), textPaint)
        }

        shootRect.set(w - dp(186f), panelTop + dp(18f), w - dp(26f), h - dp(28f))
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
        if (state.winner >= 0) {
            if (event.actionMasked == MotionEvent.ACTION_UP) actions.onExit()
            return true
        }
        if (event.x >= exitRect.left && event.x <= exitRect.right &&
            event.y >= exitRect.top && event.y <= exitRect.bottom
        ) {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) actions.onExit()
            return true
        }
        if (!state.started) return true

        val bottomZone = height * 0.74f
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                if (event.y >= bottomZone) {
                    if (event.x >= width * 0.64f) {
                        if (event.actionMasked == MotionEvent.ACTION_DOWN && state.canShoot) {
                            actions.onShoot()
                        }
                    } else {
                        val left = dp(26f)
                        val right = width * 0.62f
                        power = ((event.x - left) / (right - left)).coerceIn(0f, 1f)
                        invalidate()
                    }
                } else {
                    actions.onAim(event.x, event.y)
                }
            }
            MotionEvent.ACTION_UP -> Unit
        }
        return true
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
