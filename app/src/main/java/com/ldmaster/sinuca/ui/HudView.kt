package com.ldmaster.sinuca.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

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
        fun onTrajectoryToggle(enabled: Boolean)
        fun onShotParams(power: Float, strikeX: Float, strikeY: Float)
    }

    var power: Float = 0.55f
        private set
    var strikeX: Float = 0f
        private set
    var strikeY: Float = 0f
        private set
    var trajectoryEnabled: Boolean = false
        private set

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.2f)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.LEFT
    }
    private val path = Path()

    private val shootRect = RectF()
    private val powerRect = RectF()
    private var spinCenterX = 0f
    private var spinCenterY = 0f
    private var spinRadius = 0f
    private var cameraCenterX = 0f
    private var cameraCenterY = 0f
    private var trajectoryCenterX = 0f
    private var trajectoryCenterY = 0f
    private var exitCenterX = 0f
    private var exitCenterY = 0f

    private var gesture = GESTURE_NONE
    private var lastTouchX = 0f

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val state = provider()
        val w = width.toFloat()
        val h = height.toFloat()

        drawPotted(canvas, state)
        drawStatus(canvas, state, w)
        drawCorners(canvas, state, w)

        if (!state.started) {
            drawCenter(canvas, w, h, "Conectando ao host…")
        } else if (state.winner >= 0) {
            drawWinner(canvas, state, w, h)
        } else {
            drawPower(canvas, w, h)
            drawShoot(canvas, state, w, h)
            drawSpin(canvas, w, h)
        }
        postInvalidateOnAnimation()
    }

    private fun drawPotted(canvas: Canvas, state: HudState) {
        val localPotted: List<Int>
        val opponentPotted: List<Int>
        if (state.practice) {
            localPotted = state.potted0
            opponentPotted = state.potted1
        } else if (state.localPlayer == 1) {
            localPotted = state.potted1
            opponentPotted = state.potted0
        } else {
            localPotted = state.potted0
            opponentPotted = state.potted1
        }
        drawPottedRow(canvas, "V", localPotted, dp(18f))
        drawPottedRow(canvas, "A", opponentPotted, dp(36f))
    }

    private fun drawPottedRow(canvas: Canvas, label: String, potted: List<Int>, centerY: Float) {
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.textSize = dp(8f)
        textPaint.color = Color.parseColor("#99FFFFFF")
        canvas.drawText(label, dp(12f), centerY + dp(3f), textPaint)

        val radius = dp(5f)
        var x = dp(24f) + radius
        for (number in potted) {
            fill.color = ballColor(number)
            canvas.drawCircle(x, centerY, radius, fill)
            if (number in 9..15) {
                fill.color = Color.parseColor("#F2F6FA")
                canvas.drawRoundRect(
                    x - radius * 0.75f,
                    centerY - dp(1.8f),
                    x + radius * 0.75f,
                    centerY + dp(1.8f),
                    dp(1.5f),
                    dp(1.5f),
                    fill
                )
            }
            stroke.color = Color.parseColor("#66000000")
            stroke.strokeWidth = dp(0.8f)
            canvas.drawCircle(x, centerY, radius, stroke)
            x += dp(12f)
        }
    }

    private fun drawStatus(canvas: Canvas, state: HudState, w: Float) {
        val status = when {
            state.moving -> "BOLAS EM MOVIMENTO"
            state.practice -> "VEZ DO JOGADOR ${state.turn + 1}"
            state.canShoot -> "SUA VEZ"
            else -> "VEZ DO ADVERSÁRIO"
        }
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.textSize = dp(10f)
        val width = textPaint.measureText(status)
        fill.color = Color.parseColor("#66000000")
        canvas.drawRoundRect(
            w / 2f - width / 2f - dp(7f),
            dp(8f),
            w / 2f + width / 2f + dp(7f),
            dp(27f),
            dp(9f),
            dp(9f),
            fill
        )
        textPaint.color = Color.parseColor("#E6F2F6FA")
        canvas.drawText(status, w / 2f, dp(21.5f), textPaint)

        if (state.message.isNotEmpty() && !state.moving) {
            textPaint.textSize = dp(9f)
            textPaint.color = Color.parseColor("#B3C7D2DE")
            canvas.drawText(state.message, w / 2f, dp(41f), textPaint)
        }
    }

    private fun drawCorners(canvas: Canvas, state: HudState, w: Float) {
        val cy = dp(22f)
        trajectoryCenterX = w - dp(98f)
        trajectoryCenterY = cy
        cameraCenterX = w - dp(60f)
        cameraCenterY = cy
        exitCenterX = w - dp(22f)
        exitCenterY = cy

        if (trajectoryEnabled) {
            fill.color = Color.parseColor("#5534D399")
            canvas.drawCircle(trajectoryCenterX, trajectoryCenterY, dp(13f), fill)
        }
        stroke.color = if (trajectoryEnabled) {
            Color.parseColor("#7FF0B6")
        } else {
            Color.parseColor("#B3FFFFFF")
        }
        stroke.strokeWidth = dp(1.6f)
        path.reset()
        path.moveTo(trajectoryCenterX - dp(7f), trajectoryCenterY + dp(5f))
        path.cubicTo(
            trajectoryCenterX - dp(2f), trajectoryCenterY - dp(6f),
            trajectoryCenterX + dp(1f), trajectoryCenterY + dp(4f),
            trajectoryCenterX + dp(7f), trajectoryCenterY - dp(5f)
        )
        canvas.drawPath(path, stroke)
        fill.color = stroke.color
        canvas.drawCircle(trajectoryCenterX + dp(7f), trajectoryCenterY - dp(5f), dp(1.6f), fill)

        stroke.strokeWidth = dp(1.8f)
        stroke.color = Color.parseColor("#B3FFFFFF")
        canvas.drawRoundRect(
            cameraCenterX - dp(8f),
            cameraCenterY - dp(5f),
            cameraCenterX + dp(8f),
            cameraCenterY + dp(1f),
            dp(3f),
            dp(3f),
            stroke
        )
        canvas.drawRoundRect(
            cameraCenterX - dp(3f),
            cameraCenterY - dp(8f),
            cameraCenterX + dp(3f),
            cameraCenterY - dp(4f),
            dp(1.5f),
            dp(1.5f),
            stroke
        )
        canvas.drawCircle(cameraCenterX, cameraCenterY - dp(2f), dp(4f), stroke)
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.textSize = dp(7f)
        textPaint.color = Color.parseColor("#99FFFFFF")
        canvas.drawText("${state.cameraMode + 1}", cameraCenterX, cameraCenterY + dp(11f), textPaint)

        stroke.strokeWidth = dp(1.8f)
        stroke.color = Color.parseColor("#B3FFFFFF")
        canvas.drawLine(
            exitCenterX - dp(5f), exitCenterY - dp(5f),
            exitCenterX + dp(5f), exitCenterY + dp(5f), stroke
        )
        canvas.drawLine(
            exitCenterX + dp(5f), exitCenterY - dp(5f),
            exitCenterX - dp(5f), exitCenterY + dp(5f), stroke
        )
    }

    private fun drawPower(canvas: Canvas, w: Float, h: Float) {
        powerRect.set(w - dp(22f), dp(52f), w - dp(16f), h - dp(78f))
        fill.color = Color.parseColor("#33FFFFFF")
        canvas.drawRoundRect(powerRect, dp(4f), dp(4f), fill)

        val clamped = power.coerceIn(0f, 1f)
        val level = RectF(powerRect)
        level.top = powerRect.bottom - powerRect.height() * clamped
        if (level.height() > dp(1f)) {
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
            canvas.drawRoundRect(level, dp(4f), dp(4f), fill)
            fill.shader = null
        }
        fill.color = Color.WHITE
        canvas.drawCircle(powerRect.centerX(), level.top, dp(3.5f), fill)

        textPaint.textAlign = Paint.Align.RIGHT
        textPaint.textSize = dp(8f)
        textPaint.color = Color.parseColor("#CCFFFFFF")
        canvas.drawText(
            "${(clamped * 100).toInt()}%",
            powerRect.left - dp(4f),
            level.top + dp(3f),
            textPaint
        )
    }

    private fun drawShoot(canvas: Canvas, state: HudState, w: Float, h: Float) {
        val cx = w - dp(42f)
        val cy = h - dp(42f)
        val radius = dp(26f)
        shootRect.set(cx - radius, cy - radius, cx + radius, cy + radius)

        fill.color = if (state.canShoot) {
            Color.parseColor("#E62ECC71")
        } else {
            Color.parseColor("#802A3542")
        }
        canvas.drawCircle(cx, cy, radius, fill)
        stroke.color = Color.parseColor("#33FFFFFF")
        stroke.strokeWidth = dp(1f)
        canvas.drawCircle(cx, cy, radius, stroke)

        textPaint.textAlign = Paint.Align.CENTER
        textPaint.textSize = dp(8f)
        textPaint.color = if (state.canShoot) Color.parseColor("#062015") else Color.parseColor("#708196")
        canvas.drawText("ATIRAR", cx, cy + dp(3f), textPaint)
    }

    private fun drawSpin(canvas: Canvas, w: Float, h: Float) {
        spinCenterX = dp(42f)
        spinCenterY = h - dp(42f)
        spinRadius = dp(27f)

        textPaint.textAlign = Paint.Align.CENTER
        textPaint.textSize = dp(8f)
        textPaint.color = Color.parseColor("#99FFFFFF")
        canvas.drawText("EFEITO", spinCenterX, spinCenterY - spinRadius - dp(6f), textPaint)

        fill.color = Color.parseColor("#F7F7F2")
        canvas.drawCircle(spinCenterX, spinCenterY, spinRadius, fill)
        stroke.color = Color.parseColor("#55000000")
        stroke.strokeWidth = dp(1f)
        canvas.drawCircle(spinCenterX, spinCenterY, spinRadius, stroke)

        stroke.color = Color.parseColor("#22000000")
        stroke.strokeWidth = dp(0.8f)
        canvas.drawLine(
            spinCenterX - spinRadius, spinCenterY,
            spinCenterX + spinRadius, spinCenterY, stroke
        )
        canvas.drawLine(
            spinCenterX, spinCenterY - spinRadius,
            spinCenterX, spinCenterY + spinRadius, stroke
        )

        val markerX = spinCenterX + strikeX * spinRadius * 0.68f
        val markerY = spinCenterY + strikeY * spinRadius * 0.68f
        fill.color = Color.parseColor("#E74C3C")
        canvas.drawCircle(markerX, markerY, dp(3.4f), fill)
        stroke.color = Color.parseColor("#66FFFFFF")
        stroke.strokeWidth = dp(0.8f)
        canvas.drawCircle(markerX, markerY, dp(3.4f), stroke)
    }

    private fun drawCenter(canvas: Canvas, w: Float, h: Float, message: String) {
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.textSize = dp(15f)
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
        textPaint.textSize = dp(15f)
        textPaint.color = Color.parseColor("#F2F6FA")
        canvas.drawText(state.message, w / 2f, h / 2f + dp(22f), textPaint)
        textPaint.textSize = dp(13f)
        textPaint.color = Color.parseColor("#9AA8B8")
        canvas.drawText("Toque para voltar ao menu", w / 2f, h / 2f + dp(62f), textPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val state = provider()
        val w = width.toFloat()
        val h = height.toFloat()
        val x = event.x
        val y = event.y

        if (state.winner >= 0) {
            if (event.actionMasked == MotionEvent.ACTION_UP) actions.onExit()
            return true
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (near(x, y, trajectoryCenterX, trajectoryCenterY, dp(18f))) {
                    trajectoryEnabled = !trajectoryEnabled
                    actions.onTrajectoryToggle(trajectoryEnabled)
                    invalidate()
                    return true
                }
                if (near(x, y, cameraCenterX, cameraCenterY, dp(18f))) {
                    actions.onCamera()
                    return true
                }
                if (near(x, y, exitCenterX, exitCenterY, dp(16f))) {
                    actions.onExit()
                    return true
                }
                if (!state.started) return true

                if (hypot(x - shootRect.centerX(), y - shootRect.centerY()) <= shootRect.width() / 2f) {
                    if (state.canShoot) actions.onShoot()
                    return true
                }
                if (hypot(x - spinCenterX, y - spinCenterY) <= spinRadius) {
                    gesture = GESTURE_SPIN
                    updateSpin(x, y)
                    return true
                }
                if (x >= w - dp(64f) && y < h - dp(78f) && y > dp(48f)) {
                    gesture = GESTURE_POWER
                    updatePower(y)
                    return true
                }
                if (y > dp(48f)) {
                    gesture = GESTURE_AIM
                    lastTouchX = x
                }
            }
            MotionEvent.ACTION_MOVE -> {
                when (gesture) {
                    GESTURE_AIM -> {
                        val delta = x - lastTouchX
                        lastTouchX = x
                        if (abs(delta) > 0.01f) actions.onAimDelta(delta)
                    }
                    GESTURE_POWER -> updatePower(y)
                    GESTURE_SPIN -> updateSpin(x, y)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                gesture = GESTURE_NONE
            }
        }
        return true
    }

    private fun updatePower(y: Float) {
        if (powerRect.height() <= 0f) return
        power = ((powerRect.bottom - y) / powerRect.height()).coerceIn(0f, 1f)
        actions.onShotParams(power, strikeX, strikeY)
        invalidate()
    }

    private fun updateSpin(x: Float, y: Float) {
        val dx = (x - spinCenterX) / (spinRadius * 0.68f)
        val dy = (y - spinCenterY) / (spinRadius * 0.68f)
        val length = hypot(dx, dy)
        val scale = if (length > 1f) 1f / length else 1f
        strikeX = dx * scale
        strikeY = dy * scale
        actions.onShotParams(power, strikeX, strikeY)
        invalidate()
    }

    private fun near(x: Float, y: Float, cx: Float, cy: Float, radius: Float): Boolean =
        hypot(x - cx, y - cy) <= radius

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
        private const val GESTURE_SPIN = 3
    }
}
