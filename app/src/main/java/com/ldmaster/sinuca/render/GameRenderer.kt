package com.ldmaster.sinuca.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.opengl.Matrix
import android.util.Log
import com.ldmaster.sinuca.game.Aim
import com.ldmaster.sinuca.game.Ball
import com.ldmaster.sinuca.game.Contact
import com.ldmaster.sinuca.game.GameWorld
import com.ldmaster.sinuca.game.Table
import com.ldmaster.sinuca.game.TableTexture
import com.ldmaster.sinuca.game.TableTheme
import com.ldmaster.sinuca.game.TextureQuality
import com.ldmaster.sinuca.game.TrajectoryPredictor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

class GameRenderer(
    private val context: Context,
    private val world: GameWorld,
    private val mirrored: Boolean,
    private val theme: TableTheme = TableTheme.DEFAULT,
    private val textureStyle: TableTexture = TableTexture.DEFAULT,
    private val textureQuality: TextureQuality = TextureQuality.DEFAULT
) : GLSurfaceView.Renderer {

    @Volatile
    var localPlayer: Int = 0

    @Volatile
    var cameraMode: Int = 0
        private set

    @Volatile
    var showTrajectory: Boolean = false

    @Volatile
    private var shotPower: Float = 0.55f

    @Volatile
    private var strikeX: Float = 0f

    @Volatile
    private var strikeY: Float = 0f

    private var width = 1
    private var height = 1
    private val viewProjection = FloatArray(16)
    private val projection = FloatArray(16)
    private val view = FloatArray(16)
    private val eye = FloatArray(3)
    private val translation = FloatArray(16)
    private val ballTextures = IntArray(16)
    private val predictor = TrajectoryPredictor()
    private var lastPredictionKey = Long.MIN_VALUE

    private var program = 0
    private var lineProgram = 0
    private var tableMesh: GpuMesh? = null
    private var cueMesh: GpuMesh? = null
    private var sphereMesh: GpuMesh? = null
    private var floorMesh: GpuMesh? = null
    private var lineBuffer = 0
    private var cueHalfLength = 0.725f
    private var lastFrame = 0L

    private var uMvp = 0
    private var uModel = 0
    private var uColor = 0
    private var uStripe = 0
    private var uUseColor = 0
    private var uEye = 0
    private var uUseTexture = 0
    private var uTexture = 0
    private var uLightPos = 0
    private var uStyle = 0
    private var uUsePattern = 0
    private var uUseLiteral = 0
    private var uDetailMap = 0
    private var uDetailStrength = 0
    private var uDetailScale = 0
    private var uDetailBase = 0
    private var detailTexture = 0
    private var uLineMvp = 0
    private var uLineColor = 0

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glClearColor(0.016f, 0.023f, 0.038f, 1f)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)

        program = linkProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        uMvp = GLES30.glGetUniformLocation(program, "uMVP")
        uModel = GLES30.glGetUniformLocation(program, "uModel")
        uColor = GLES30.glGetUniformLocation(program, "uColor")
        uStripe = GLES30.glGetUniformLocation(program, "uStripe")
        uUseColor = GLES30.glGetUniformLocation(program, "uUseColor")
        uEye = GLES30.glGetUniformLocation(program, "uEye")
        uUseTexture = GLES30.glGetUniformLocation(program, "uUseTexture")
        uTexture = GLES30.glGetUniformLocation(program, "uTexture")
        uLightPos = GLES30.glGetUniformLocation(program, "uLightPos")
        uStyle = GLES30.glGetUniformLocation(program, "uStyle")
        uUsePattern = GLES30.glGetUniformLocation(program, "uUsePattern")
        uUseLiteral = GLES30.glGetUniformLocation(program, "uUseLiteral")
        uDetailMap = GLES30.glGetUniformLocation(program, "uDetailMap")
        uDetailStrength = GLES30.glGetUniformLocation(program, "uDetailStrength")
        uDetailScale = GLES30.glGetUniformLocation(program, "uDetailScale")
        uDetailBase = GLES30.glGetUniformLocation(program, "uDetailBase")

        lineProgram = linkProgram(LINE_VERTEX_SHADER, LINE_FRAGMENT_SHADER)
        uLineMvp = GLES30.glGetUniformLocation(lineProgram, "uMVP")
        uLineColor = GLES30.glGetUniformLocation(lineProgram, "uColor")

        try {
            tableMesh = GpuMesh(loadTable())
            val cue = GlbLoader.load(context, "models/cue.glb")
            cueMesh = GpuMesh(cue)
            var maxX = 0f
            for (i in 0 until cue.vertexCount) {
                val value = abs(cue.positions[i * 3])
                if (value > maxX) maxX = value
            }
            cueHalfLength = maxX
        } catch (error: Exception) {
            Log.e(TAG, "failed to load models", error)
        }

        sphereMesh = GpuMesh(sphere(Table.BALL_RADIUS.toFloat(), segments = 64, rings = 48))
        floorMesh = GpuMesh(floor())
        for (number in 0..15) ballTextures[number] = createBallTexture(number)
        if (textureQuality != TextureQuality.PROCEDURAL) detailTexture = loadDetailTexture()


        val ids = IntArray(1)
        GLES30.glGenBuffers(1, ids, 0)
        lineBuffer = ids[0]
        lastFrame = System.nanoTime()
    }

    private fun loadTable(): MeshData = runCatching {
        GlbLoader.load(context, theme.asset)
    }.getOrElse { error ->
        Log.e(TAG, "failed to load ${theme.asset}; falling back to table.glb", error)
        GlbLoader.load(context, "models/table.glb")
    }

    override fun onSurfaceChanged(gl: GL10?, surfaceWidth: Int, surfaceHeight: Int) {
        width = surfaceWidth.coerceAtLeast(1)
        height = surfaceHeight.coerceAtLeast(1)
        GLES30.glViewport(0, 0, width, height)
        updateCamera()
    }

    fun cycleCamera() {
        cameraMode = (cameraMode + 1) % 4
        updateCamera()
    }

    private fun updateCamera() {
        val aspect = width.toFloat() / height.toFloat()
        val side = if (mirrored) 1f else -1f
        val fieldOfView: Float
        val eyeY: Float
        val eyeZ: Float
        val lookY: Float
        when (cameraMode) {
            1 -> {
                fieldOfView = 44f
                eyeY = 3.70f
                eyeZ = 1.55f * side
                lookY = 0.72f
            }
            2 -> {
                fieldOfView = 50f
                eyeY = 1.50f
                eyeZ = 3.55f * side
                lookY = 0.82f
            }
            3 -> {
                fieldOfView = 42f
                eyeY = 4.60f
                eyeZ = 0.0f
                lookY = 0.72f
            }
            else -> {
                fieldOfView = 46f
                eyeY = 2.62f
                eyeZ = 3.15f * side
                lookY = 0.70f
            }
        }
        Matrix.perspectiveM(projection, 0, fieldOfView, aspect, 0.1f, 40f)
        eye[0] = 0f
        eye[1] = eyeY
        eye[2] = eyeZ
        val upY = if (cameraMode == 3) 0f else 1f
        val upZ = if (cameraMode == 3) -1f else 0f
        Matrix.setLookAtM(
            view, 0,
            eye[0], eye[1], eye[2],
            0f, lookY, 0f,
            0f, upY, upZ
        )
        Matrix.multiplyMM(viewProjection, 0, projection, 0, view, 0)
    }

    override fun onDrawFrame(gl: GL10?) {
        val now = System.nanoTime()
        val dt = ((now - lastFrame) / 1_000_000_000.0).coerceIn(0.0, 0.05)
        lastFrame = now
        synchronized(world) { world.step(dt) }

        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)

        GLES30.glUseProgram(program)
        GLES30.glUniform3f(uEye, eye[0], eye[1], eye[2])
        GLES30.glUniform3f(uLightPos, 0.95f, 2.35f, -0.85f)
        drawMesh(floorMesh, viewProjection, IDENTITY, useVertexColor = false, stripe = false, color = WHITE)
        drawMesh(
            tableMesh, viewProjection, IDENTITY,
            useVertexColor = false, stripe = false, color = WHITE,
            textureId = detailTexture,
            useTexture = textureQuality != TextureQuality.PROCEDURAL,
            useLiteral = textureQuality != TextureQuality.PROCEDURAL,
            usePattern = textureQuality == TextureQuality.PROCEDURAL
        )

        val model = FloatArray(16)
        val mvp = FloatArray(16)
        for (ball in world.balls) {
            if (!ball.active) continue
            val speed = ball.speed
            if (!ball.falling && speed > 0.001) {
                val degrees = Math.toDegrees(speed * dt / Table.BALL_RADIUS).toFloat()
                Matrix.rotateM(
                    ball.visualRotation, 0, degrees,
                    (ball.vz / speed).toFloat(), 0f, (-ball.vx / speed).toFloat()
                )
            }
            val drop = if (ball.falling) 0.18f * ball.fall * ball.fall else 0f
            Matrix.setIdentityM(translation, 0)
            Matrix.translateM(
                translation, 0,
                ball.x.toFloat(),
                (Table.BALL_Y - drop).toFloat(),
                ball.z.toFloat()
            )
            Matrix.multiplyMM(model, 0, translation, 0, ball.visualRotation, 0)
            Matrix.multiplyMM(mvp, 0, viewProjection, 0, model, 0)
            val color = ballColor(ball.number)
            drawMesh(
                sphereMesh, mvp, model,
                useVertexColor = true,
                stripe = false,
                color = color,
                textureId = ballTextures[ball.number],
                useTexture = true
            )
        }

        drawCue(model)

        val cue = world.cueBall()
        val showAim = cue.active && !world.moving && world.winner < 0 && world.canShoot(localPlayer)
        if (showAim) {
            val directionX = cos(world.aimAngle).toFloat()
            val directionZ = sin(world.aimAngle).toFloat()
            drawAimDashes(cue, directionX, directionZ)
            val contact = Aim.firstContact(
                cue,
                world.balls,
                directionX.toDouble(),
                directionZ.toDouble()
            )
            if (contact != null) drawSightIndicator(contact)
            if (showTrajectory) drawPrediction()
        }
        GLES30.glUseProgram(0)
    }

    private fun drawAimDashes(cue: Ball, directionX: Float, directionZ: Float) {
        val startDistance = Table.BALL_RADIUS.toFloat() + 0.012f
        val endDistance = startDistance + 0.55f
        val points = ArrayList<Float>(32)
        var distance = startDistance
        while (distance < endDistance) {
            val dashEnd = (distance + 0.032f).coerceAtMost(endDistance)
            points.add(cue.x.toFloat() + directionX * distance)
            points.add(Table.BALL_Y.toFloat())
            points.add(cue.z.toFloat() + directionZ * distance)
            points.add(cue.x.toFloat() + directionX * dashEnd)
            points.add(Table.BALL_Y.toFloat())
            points.add(cue.z.toFloat() + directionZ * dashEnd)
            distance = dashEnd + 0.022f
        }
        drawLineVertices(points.toFloatArray(), 1f, 0.93f, 0.62f, 0.42f, 2f, GLES30.GL_LINES)
    }

    private fun drawSightIndicator(contact: Contact) {
        val target = contact.target
        val points = ArrayList<Float>(160)
        addCircle(points, contact.ghostX, contact.ghostZ, Table.BALL_RADIUS)
        addCircle(points, target.x, target.z, Table.BALL_RADIUS * 1.12)

        val startX = target.x + contact.normalX * (Table.BALL_RADIUS + 0.008)
        val startZ = target.z + contact.normalZ * (Table.BALL_RADIUS + 0.008)
        val endX = target.x + contact.normalX * (Table.BALL_RADIUS + 0.30)
        val endZ = target.z + contact.normalZ * (Table.BALL_RADIUS + 0.30)
        points.add(startX.toFloat())
        points.add(Table.BALL_Y.toFloat())
        points.add(startZ.toFloat())
        points.add(endX.toFloat())
        points.add(Table.BALL_Y.toFloat())
        points.add(endZ.toFloat())

        drawLineVertices(
            points.toFloatArray(),
            0.55f, 0.95f, 0.85f, 0.60f,
            2.5f,
            GLES30.GL_LINES
        )
    }

    private fun addCircle(points: ArrayList<Float>, centerX: Double, centerZ: Double, radius: Double) {
        val segments = 28
        for (index in 0 until segments) {
            val first = Math.PI * 2.0 * index / segments
            val second = Math.PI * 2.0 * (index + 1) / segments
            points.add((centerX + cos(first) * radius).toFloat())
            points.add(Table.BALL_Y.toFloat())
            points.add((centerZ + sin(first) * radius).toFloat())
            points.add((centerX + cos(second) * radius).toFloat())
            points.add(Table.BALL_Y.toFloat())
            points.add((centerZ + sin(second) * radius).toFloat())
        }
    }

    private fun drawPrediction() {
        val key = predictionKey()
        if (key != lastPredictionKey) {
            lastPredictionKey = key
            var snapshot: com.ldmaster.sinuca.game.WorldSnapshot
            var angle: Double
            synchronized(world) {
                snapshot = world.snapshot()
                angle = world.aimAngle
            }
            predictor.request(
                key,
                snapshot,
                angle,
                shotPower.toDouble(),
                strikeX.toDouble(),
                strikeY.toDouble()
            )
        }
        val prediction = predictor.latest ?: return
        if (prediction.cuePath.isNotEmpty()) {
            drawLineVertices(prediction.cuePath, 1f, 1f, 1f, 0.45f, 2f, GLES30.GL_LINE_STRIP)
        }
        if (prediction.targetPath.isNotEmpty()) {
            drawLineVertices(
                prediction.targetPath,
                0.45f, 0.95f, 0.85f, 0.70f,
                2.5f, GLES30.GL_LINE_STRIP
            )
        }
    }

    private fun predictionKey(): Long {
        var key = 17L
        key = key * 31 + world.aimAngle.toBits()
        key = key * 31 + shotPower.toBits()
        key = key * 31 + strikeX.toBits()
        key = key * 31 + strikeY.toBits()
        for (ball in world.balls) {
            key = key * 31 + (ball.x * 10000.0).toLong()
            key = key * 31 + (ball.z * 10000.0).toLong()
            key = key * 31 + (if (ball.active) 1 else 0)
        }
        return key
    }

    private fun drawLineVertices(
        points: FloatArray,
        red: Float,
        green: Float,
        blue: Float,
        alpha: Float,
        width: Float,
        mode: Int
    ) {
        if (points.size < 6) return
        GLES30.glUseProgram(lineProgram)
        GLES30.glUniformMatrix4fv(uLineMvp, 1, false, viewProjection, 0)
        GLES30.glUniform4f(uLineColor, red, green, blue, alpha)
        val buffer = ByteBuffer.allocateDirect(points.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        buffer.put(points).position(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, lineBuffer)
        GLES30.glBufferData(
            GLES30.GL_ARRAY_BUFFER,
            points.size * 4,
            buffer,
            GLES30.GL_DYNAMIC_DRAW
        )
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 0, 0)
        GLES30.glLineWidth(width)
        GLES30.glDrawArrays(mode, 0, points.size / 3)
        GLES30.glDisableVertexAttribArray(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
    }

    private fun drawCue(model: FloatArray) {
        val mesh = cueMesh ?: return
        val cue = world.cueBall()
        if (!cue.active || world.winner >= 0 || world.moving) return
        if (!world.canShoot(localPlayer)) return
        val directionX = cos(world.aimAngle)
        val directionZ = sin(world.aimAngle)
        val offset = cueHalfLength + Table.BALL_RADIUS + 0.02
        val centerX = cue.x - directionX * offset
        val centerZ = cue.z - directionZ * offset
        val theta = atan2(directionZ, -directionX)

        Matrix.setIdentityM(model, 0)
        Matrix.translateM(model, 0, centerX.toFloat(), Table.BALL_Y.toFloat(), centerZ.toFloat())
        Matrix.rotateM(model, 0, Math.toDegrees(theta).toFloat(), 0f, 1f, 0f)
        val mvp = FloatArray(16)
        Matrix.multiplyMM(mvp, 0, viewProjection, 0, model, 0)
        GLES30.glUseProgram(program)
        GLES30.glUniform3f(uEye, eye[0], eye[1], eye[2])
        drawMesh(mesh, mvp, model, useVertexColor = false, stripe = false, color = WHITE)
    }

    private fun drawMesh(
        mesh: GpuMesh?,
        mvp: FloatArray,
        model: FloatArray,
        useVertexColor: Boolean,
        stripe: Boolean,
        color: FloatArray,
        textureId: Int = 0,
        useTexture: Boolean = false,
        useLiteral: Boolean = false,
        usePattern: Boolean = false
    ) {
        if (mesh == null) return
        GLES30.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
        GLES30.glUniformMatrix4fv(uModel, 1, false, model, 0)
        GLES30.glUniform1f(uUseColor, if (useVertexColor) 1f else 0f)
        GLES30.glUniform1f(uStripe, if (stripe) 1f else 0f)
        GLES30.glUniform1f(uUseTexture, if (useTexture) 1f else 0f)
        GLES30.glUniform1f(uUseLiteral, if (useLiteral) 1f else 0f)
        GLES30.glUniform1f(uUsePattern, if (usePattern) 1f else 0f)
        GLES30.glUniform1f(uStyle, textureStyle.ordinal.toFloat())
        val strength = when (textureQuality) {
            TextureQuality.DETAILED_1080 -> 1.0f
            TextureQuality.DETAILED_2K -> 1.1f
            else -> 1.0f
        }
        val scale = when (textureQuality) {
            TextureQuality.DETAILED_1080 -> 1.0f
            TextureQuality.DETAILED_2K -> 1.5f
            else -> 2.0f
        }
        val baseMix = when (textureQuality) {
            TextureQuality.DETAILED_1080 -> 0.0f
            TextureQuality.DETAILED_2K -> 0.25f
            else -> 0.5f
        }
        GLES30.glUniform1f(uDetailStrength, strength)
        GLES30.glUniform1f(uDetailScale, scale)
        GLES30.glUniform1f(uDetailBase, baseMix)
        GLES30.glUniform3f(uColor, color[0], color[1], color[2])
        if (useTexture) {
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)
            GLES30.glUniform1i(uTexture, 0)
            GLES30.glUniform1i(uDetailMap, 0)
        }
        mesh.draw()
        if (useTexture) GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
    }

    fun rotateAim(deltaDp: Float) {
        synchronized(world) { world.aimAngle += deltaDp * AIM_SENSITIVITY_DP }
    }

    fun release() {
        predictor.release()
    }

    fun setShotParams(power: Float, strikeX: Float, strikeY: Float) {
        shotPower = power.coerceIn(0f, 1f)
        this.strikeX = strikeX.coerceIn(-1f, 1f)
        this.strikeY = strikeY.coerceIn(-1f, 1f)
        lastPredictionKey = Long.MIN_VALUE
    }

    private fun ballColor(number: Int): FloatArray = when (number) {
        0 -> linear(0.97f, 0.97f, 0.95f)
        1, 9 -> linear(0.93f, 0.72f, 0.12f)
        2, 10 -> linear(0.10f, 0.28f, 0.75f)
        3, 11 -> linear(0.82f, 0.12f, 0.10f)
        4, 12 -> linear(0.35f, 0.12f, 0.55f)
        5, 13 -> linear(0.95f, 0.45f, 0.06f)
        6, 14 -> linear(0.05f, 0.42f, 0.18f)
        7, 15 -> linear(0.48f, 0.09f, 0.10f)
        else -> linear(0.06f, 0.06f, 0.06f)
    }

    private fun linear(r: Float, g: Float, b: Float): FloatArray =
        floatArrayOf(r.pow(2.2f), g.pow(2.2f), b.pow(2.2f))

    private fun ballPaintColor(number: Int): Int = when (number) {
        0 -> Color.rgb(247, 247, 242)
        1, 9 -> Color.rgb(232, 178, 26)
        2, 10 -> Color.rgb(30, 79, 168)
        3, 11 -> Color.rgb(192, 40, 30)
        4, 12 -> Color.rgb(90, 42, 122)
        5, 13 -> Color.rgb(224, 106, 18)
        6, 14 -> Color.rgb(15, 107, 52)
        7, 15 -> Color.rgb(122, 28, 28)
        else -> Color.rgb(20, 20, 20)
    }

    private fun loadDetailTexture(): Int {
        val name = "textures/detail_${textureStyle.key}.jpg"
        return try {
            context.assets.open(name).use { stream ->
                val sampleSize = when (textureQuality) {
                    TextureQuality.DETAILED_1080, TextureQuality.DETAILED_2K -> 2
                    else -> 1
                }
                val options = BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                }
                val decoded = BitmapFactory.decodeStream(stream, null, options) ?: return 0
                val target = when (textureQuality) {
                    TextureQuality.DETAILED_1080 -> 1080
                    TextureQuality.DETAILED_2K -> 2048
                    else -> decoded.width
                }
                val bitmap = if (decoded.width != target) {
                    Bitmap.createScaledBitmap(decoded, target, target, true).also {
                        if (it !== decoded) decoded.recycle()
                    }
                } else {
                    decoded
                }
                val max = IntArray(1)
                GLES30.glGetIntegerv(GLES30.GL_MAX_TEXTURE_SIZE, max, 0)
                if (bitmap.width > max[0] || bitmap.height > max[0]) {
                    Log.w(TAG, "$name ${bitmap.width}px exceeds GL max ${max[0]}px")
                    bitmap.recycle()
                    return 0
                }
                val ids = IntArray(1)
                GLES30.glGenTextures(1, ids, 0)
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, ids[0])
                GLES30.glTexParameteri(
                    GLES30.GL_TEXTURE_2D,
                    GLES30.GL_TEXTURE_MIN_FILTER,
                    GLES30.GL_LINEAR_MIPMAP_LINEAR
                )
                GLES30.glTexParameteri(
                    GLES30.GL_TEXTURE_2D,
                    GLES30.GL_TEXTURE_MAG_FILTER,
                    GLES30.GL_LINEAR
                )
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_REPEAT)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_REPEAT)
                GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)
                GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
                Log.i(TAG, "loaded $name ${bitmap.width}x${bitmap.height}")
                bitmap.recycle()
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
                ids[0]
            }
        } catch (error: Exception) {
            Log.e(TAG, "failed to load $name", error)
            0
        }
    }

    private fun createBallTexture(number: Int): Int {
        val width = 768
        val height = 384
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val base = ballPaintColor(number)

        if (number == 0) {
            canvas.drawColor(Color.WHITE)
            paint.color = Color.rgb(214, 44, 44)
            canvas.drawCircle(width * 0.5f, height * 0.5f, height * 0.03f, paint)
        } else if (number in 9..15) {
            canvas.drawColor(Color.WHITE)
            paint.color = base
            canvas.drawRect(0f, height * 0.26f, width.toFloat(), height * 0.74f, paint)
        } else {
            canvas.drawColor(base)
        }

        if (number != 0) {
            val patchCenterY = height * 0.50f
            val patchRadius = height * 0.095f
            paint.color = Color.WHITE
            paint.style = Paint.Style.FILL
            for (centerX in floatArrayOf(width * 0.25f, width * 0.75f)) {
                canvas.drawCircle(centerX, patchCenterY, patchRadius, paint)
            }
            paint.color = Color.BLACK
            paint.textSize = height * 0.120f
            paint.typeface = Typeface.DEFAULT_BOLD
            paint.textAlign = Paint.Align.CENTER
            for (centerX in floatArrayOf(width * 0.25f, width * 0.75f)) {
                canvas.drawText(
                    number.toString(),
                    centerX,
                    patchCenterY + height * 0.038f,
                    paint
                )
            }
        }

        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, ids[0])
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_REPEAT)
        GLES30.glTexParameteri(
            GLES30.GL_TEXTURE_2D,
            GLES30.GL_TEXTURE_WRAP_T,
            GLES30.GL_CLAMP_TO_EDGE
        )
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        bitmap.recycle()
        return ids[0]
    }

    private fun sphere(radius: Float, segments: Int = 48, rings: Int = 32): MeshData {
        val positions = ArrayList<Float>()
        val normals = ArrayList<Float>()
        val colors = ArrayList<Float>()
        val uvs = ArrayList<Float>()
        val indices = ArrayList<Int>()
        for (ring in 0..rings) {
            val theta = Math.PI * ring / rings
            val y = cos(theta)
            val ringRadius = sin(theta)
            for (segment in 0..segments) {
                val phi = 2.0 * Math.PI * segment / segments
                val x = ringRadius * cos(phi)
                val z = ringRadius * sin(phi)
                positions.add((x * radius).toFloat())
                positions.add((y * radius).toFloat())
                positions.add((z * radius).toFloat())
                normals.add(x.toFloat())
                normals.add(y.toFloat())
                normals.add(z.toFloat())
                colors.add(1f)
                colors.add(1f)
                colors.add(1f)
                uvs.add(1f - segment.toFloat() / segments)
                uvs.add(1f - ring.toFloat() / rings)
            }
        }
        for (ring in 0 until rings) {
            for (segment in 0 until segments) {
                val a = ring * (segments + 1) + segment
                val b = a + segments + 1
                indices.add(a)
                indices.add(b)
                indices.add(a + 1)
                indices.add(a + 1)
                indices.add(b)
                indices.add(b + 1)
            }
        }
        return MeshData(
            positions.toFloatArray(),
            normals.toFloatArray(),
            colors.toFloatArray(),
            ShortArray(indices.size) { indices[it].toShort() },
            uvs.toFloatArray()
        )
    }

    private fun floor(): MeshData {
        val size = 9f
        return MeshData(
            floatArrayOf(
                -size, 0f, -size,
                size, 0f, -size,
                size, 0f, size,
                -size, 0f, size
            ),
            floatArrayOf(
                0f, 1f, 0f,
                0f, 1f, 0f,
                0f, 1f, 0f,
                0f, 1f, 0f
            ),
            floatArrayOf(
                0.055f, 0.065f, 0.085f,
                0.055f, 0.065f, 0.085f,
                0.055f, 0.065f, 0.085f,
                0.055f, 0.065f, 0.085f
            ),
            shortArrayOf(0, 1, 2, 0, 2, 3)
        )
    }

    private fun linkProgram(vertexSource: String, fragmentSource: String): Int {
        val vertex = compile(GLES30.GL_VERTEX_SHADER, vertexSource)
        val fragment = compile(GLES30.GL_FRAGMENT_SHADER, fragmentSource)
        val id = GLES30.glCreateProgram()
        GLES30.glAttachShader(id, vertex)
        GLES30.glAttachShader(id, fragment)
        GLES30.glLinkProgram(id)
        val status = IntArray(1)
        GLES30.glGetProgramiv(id, GLES30.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES30.glGetProgramInfoLog(id)
            throw IllegalStateException("program link failed: $log")
        }
        GLES30.glDeleteShader(vertex)
        GLES30.glDeleteShader(fragment)
        return id
    }

    private fun compile(type: Int, source: String): Int {
        val id = GLES30.glCreateShader(type)
        GLES30.glShaderSource(id, source)
        GLES30.glCompileShader(id)
        val status = IntArray(1)
        GLES30.glGetShaderiv(id, GLES30.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES30.glGetShaderInfoLog(id)
            GLES30.glDeleteShader(id)
            throw IllegalStateException("shader compile failed: $log")
        }
        return id
    }

    private class GpuMesh(mesh: MeshData) {
        private val vertexBuffer: IntArray
        private val indexBuffer: IntArray
        private val count: Int

        init {
            val stride = 11
            val vertexData = FloatArray(mesh.vertexCount * stride)
            var at = 0
            for (i in 0 until mesh.vertexCount) {
                vertexData[at++] = mesh.positions[i * 3]
                vertexData[at++] = mesh.positions[i * 3 + 1]
                vertexData[at++] = mesh.positions[i * 3 + 2]
                vertexData[at++] = mesh.normals[i * 3]
                vertexData[at++] = mesh.normals[i * 3 + 1]
                vertexData[at++] = mesh.normals[i * 3 + 2]
                vertexData[at++] = mesh.colors[i * 3]
                vertexData[at++] = mesh.colors[i * 3 + 1]
                vertexData[at++] = mesh.colors[i * 3 + 2]
                vertexData[at++] = mesh.uvs[i * 2]
                vertexData[at++] = mesh.uvs[i * 2 + 1]
            }
            val vertices = ByteBuffer.allocateDirect(vertexData.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
            vertices.put(vertexData).position(0)
            val indices = ByteBuffer.allocateDirect(mesh.indices.size * 2)
                .order(ByteOrder.nativeOrder())
                .asShortBuffer()
            indices.put(mesh.indices).position(0)

            vertexBuffer = IntArray(1)
            indexBuffer = IntArray(1)
            GLES30.glGenBuffers(1, vertexBuffer, 0)
            GLES30.glGenBuffers(1, indexBuffer, 0)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vertexBuffer[0])
            GLES30.glBufferData(
                GLES30.GL_ARRAY_BUFFER,
                vertexData.size * 4,
                vertices,
                GLES30.GL_STATIC_DRAW
            )
            GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, indexBuffer[0])
            GLES30.glBufferData(
                GLES30.GL_ELEMENT_ARRAY_BUFFER,
                mesh.indices.size * 2,
                indices,
                GLES30.GL_STATIC_DRAW
            )
            count = mesh.indices.size
        }

        fun draw() {
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vertexBuffer[0])
            GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, indexBuffer[0])
            val stride = 11 * 4
            GLES30.glEnableVertexAttribArray(0)
            GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, stride, 0)
            GLES30.glEnableVertexAttribArray(1)
            GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, stride, 3 * 4)
            GLES30.glEnableVertexAttribArray(2)
            GLES30.glVertexAttribPointer(2, 3, GLES30.GL_FLOAT, false, stride, 6 * 4)
            GLES30.glEnableVertexAttribArray(3)
            GLES30.glVertexAttribPointer(3, 2, GLES30.GL_FLOAT, false, stride, 9 * 4)
            GLES30.glDrawElements(GLES30.GL_TRIANGLES, count, GLES30.GL_UNSIGNED_SHORT, 0)
            GLES30.glDisableVertexAttribArray(0)
            GLES30.glDisableVertexAttribArray(1)
            GLES30.glDisableVertexAttribArray(2)
            GLES30.glDisableVertexAttribArray(3)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
            GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, 0)
        }
    }

    companion object {
        private const val TAG = "GameRenderer"
        private const val AIM_SENSITIVITY_DP = 0.0032f

        private val IDENTITY = floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, 1f, 0f,
            0f, 0f, 0f, 1f
        )
        private val WHITE = floatArrayOf(1f, 1f, 1f)

        private const val VERTEX_SHADER = """#version 300 es
layout(location = 0) in vec3 aPos;
layout(location = 1) in vec3 aNormal;
layout(location = 2) in vec3 aColor;
layout(location = 3) in vec2 aUv;
uniform mat4 uMVP;
uniform mat4 uModel;
out vec3 vNormal;
out vec3 vColor;
out vec3 vLocal;
out vec3 vWorld;
out vec2 vUv;
void main() {
    vec4 world = uModel * vec4(aPos, 1.0);
    vWorld = world.xyz;
    vNormal = mat3(uModel) * aNormal;
    vColor = aColor;
    vLocal = aPos;
    vUv = aUv;
    gl_Position = uMVP * vec4(aPos, 1.0);
}
"""

        private const val FRAGMENT_SHADER = """#version 300 es
precision highp float;
in vec3 vNormal;
in vec3 vColor;
in vec3 vLocal;
in vec3 vWorld;
in vec2 vUv;
uniform vec3 uColor;
uniform float uStripe;
uniform float uUseColor;
uniform float uUseTexture;
uniform float uStyle;
uniform float uUsePattern;
uniform float uUseLiteral;
uniform float uDetailStrength;
uniform float uDetailScale;
uniform float uDetailBase;
uniform sampler2D uTexture;
uniform sampler2D uDetailMap;
uniform vec3 uEye;
uniform vec3 uLightPos;
out vec4 fragColor;

float hash21(vec2 p) {
    p = fract(p * vec2(123.34, 456.21));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
}

float vnoise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    float a = hash21(i);
    float b = hash21(i + vec2(1.0, 0.0));
    float c = hash21(i + vec2(0.0, 1.0));
    float d = hash21(i + vec2(1.0, 1.0));
    return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
}

float fbm(vec2 p) {
    float value = 0.0;
    float amplitude = 0.5;
    for (int i = 0; i < 5; i++) {
        value += amplitude * vnoise(p);
        p *= 2.03;
        amplitude *= 0.5;
    }
    return value;
}

void main() {
    vec3 base;
    if (uUseLiteral > 0.5) {
        base = mix(vColor, uColor, uUseColor);
    } else if (uUseTexture > 0.5) {
        base = pow(texture(uTexture, vUv).rgb, vec3(2.2));
    } else {
        base = mix(vColor, uColor, uUseColor);
        if (uStripe > 0.5) {
            float band = smoothstep(0.55, 0.40, abs(normalize(vLocal).y));
            base = mix(vec3(0.93), base, band);
        }
    }
    if (uUseTexture < 0.5) {
        float wood = smoothstep(0.08, 0.13, base.r) *
            (1.0 - smoothstep(0.06, 0.10, base.g));
        float grain = 0.5 + 0.5 * sin(
            vWorld.x * 22.0 + vWorld.y * 11.0 + 1.5 * sin(vWorld.z * 9.0)
        );
        base *= mix(1.0, 0.96 + 0.08 * grain, wood);
    }

    vec3 n = normalize(vNormal);
    float mapRoughness = 0.5;
    if (uUseLiteral > 0.5) {
        vec3 d = texture(uDetailMap, vUv * uDetailScale).rgb;
        float height = d.r;
        float roughness = d.g;
        float ao = d.b;
        base *= 1.0 + (height - 0.5) * 0.90 * uDetailStrength * uDetailBase;
        base *= 0.85 + 0.15 * ao;
        n = normalize(n + vec3(
            (height - 0.5) * 0.80 * uDetailStrength,
            0.0,
            (roughness - 0.5) * 0.50 * uDetailStrength
        ));
        mapRoughness = roughness;
    } else if (uUsePattern > 0.5) {
        vec3 p = vWorld * 10.0;
        float grain = sin(p.x * 55.0 + fbm(p.xz * 2.5) * 9.0);
        float weave = sin(p.x * 210.0) * sin(p.z * 210.0);
        float brushed = fbm(vec2(p.x * 1.5, p.z * 80.0));
        float macro = fbm(p.xz * 0.35);
        float scratches = smoothstep(0.55, 0.90, fbm(vec2(p.x * 3.0, p.z * 90.0)));
        float chalk = smoothstep(0.60, 0.85, fbm(p.xz * 1.8));
        float detail;
        float bump;
        if (uStyle < 0.5) {
            detail = grain * 0.060 + weave * 0.035 + brushed * 0.030;
            bump = grain * 0.50 + weave * 0.30;
        } else if (uStyle < 1.5) {
            detail = grain * 0.070 + weave * 0.040 + scratches * 0.130 + chalk * 0.060;
            bump = grain * 0.60 + scratches * 0.80;
        } else if (uStyle < 2.5) {
            detail = grain * 0.025 + weave * 0.020;
            bump = grain * 0.25;
        } else if (uStyle < 3.5) {
            detail = grain * 0.050 + weave * 0.030 + (macro - 0.5) * 0.100;
            bump = grain * 0.45 + macro * 0.30;
        } else {
            detail = grain * 0.035 + weave * 0.025;
            bump = grain * 0.35;
        }
        base *= 1.0 + detail;
        n = normalize(n + vec3(grain * bump * 0.060, 0.0, weave * bump * 0.060));
    }
    vec3 l = normalize(vec3(0.35, 1.0, 0.25));
    vec3 v = normalize(uEye - vWorld);
    vec3 h = normalize(l + v);
    float diffuse = max(dot(n, l), 0.0);
    float luminance = dot(base, vec3(0.2126, 0.7152, 0.0722));
    float gloss = clamp(luminance * 5.0, 0.0, 1.0) *
        mix(1.0, 0.55, mapRoughness);

    vec3 toLight = uLightPos - vWorld;
    float distanceToLight = length(toLight);
    vec3 pl = toLight / max(distanceToLight, 0.001);
    float attenuation = 1.0 / (1.0 + 0.30 * distanceToLight * distanceToLight);
    float pointDiffuse = max(dot(n, pl), 0.0) * attenuation;
    vec3 pointHalf = normalize(pl + v);
    float pointSpecular = pow(max(dot(n, pointHalf), 0.0), 180.0) *
        0.95 * attenuation * mix(0.05, 1.0, gloss);

    float specular = pow(max(dot(n, h), 0.0), 56.0) * 0.35 * mix(0.04, 1.0, gloss);
    vec3 lit = base * (0.42 + 0.78 * diffuse + 0.25 * pointDiffuse) +
        vec3(specular + pointSpecular);
    fragColor = vec4(pow(clamp(lit, 0.0, 1.0), vec3(1.0 / 2.2)), 1.0);
}
"""

        private const val LINE_VERTEX_SHADER = """#version 300 es
layout(location = 0) in vec3 aPos;
uniform mat4 uMVP;
void main() {
    gl_Position = uMVP * vec4(aPos, 1.0);
}
"""

        private const val LINE_FRAGMENT_SHADER = """#version 300 es
precision mediump float;
uniform vec4 uColor;
out vec4 fragColor;
void main() {
    fragColor = uColor;
}
"""
    }
}
