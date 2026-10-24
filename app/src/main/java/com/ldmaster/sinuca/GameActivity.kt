package com.ldmaster.sinuca

import android.app.Activity
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import com.ldmaster.sinuca.game.AiLevel
import com.ldmaster.sinuca.game.AiPlayer
import com.ldmaster.sinuca.game.GameWorld
import com.ldmaster.sinuca.game.TableTexture
import com.ldmaster.sinuca.game.TableTheme
import com.ldmaster.sinuca.game.TextureQuality
import com.ldmaster.sinuca.net.NetMessage
import com.ldmaster.sinuca.net.Session
import com.ldmaster.sinuca.net.Wire
import com.ldmaster.sinuca.render.GameRenderer
import com.ldmaster.sinuca.ui.HudState
import com.ldmaster.sinuca.ui.HudView

class GameActivity : Activity() {

    private lateinit var world: GameWorld
    private lateinit var renderer: GameRenderer
    private lateinit var surface: GLSurfaceView
    private lateinit var hud: HudView
    private val handler = Handler(Looper.getMainLooper())

    private var localPlayer = 0
    private var practice = false
    private var ai = false
    private var aiLevel = AiLevel.MEDIUM
    private var aiPlayer: AiPlayer? = null
    private var started = true
    private var lastSent = ""
    private var closed = false

    private val sync = object : Runnable {
        override fun run() {
            if (ai) {
                aiPlayer?.tick()
            } else {
                val service = Session.service
                if (service != null && started && localPlayer == 0) {
                    val encoded = Wire.state(world.snapshot())
                    if (encoded != lastSent) {
                        lastSent = encoded
                        service.send(encoded)
                    }
                }
            }
            handler.postDelayed(this, 60)
        }
    }

    private val gameListener = object : BluetoothGameListener() {
        override fun onMessage(message: NetMessage) {
            when (message) {
                is NetMessage.State -> {
                    started = true
                    surface.queueEvent { world.applySnapshot(message.snapshot) }
                }
                is NetMessage.Start -> {
                    localPlayer = message.youAre
                    renderer.localPlayer = message.youAre
                    started = true
                }
                is NetMessage.Shot -> {
                    surface.queueEvent {
                        if (world.canShoot(1 - localPlayer)) {
                            world.shoot(
                                message.angle,
                                message.power,
                                message.strikeX,
                                message.strikeY
                            )
                        }
                    }
                }
                is NetMessage.Bye -> runOnUiThread { finish() }
                else -> Unit
            }
        }

        override fun onDisconnected(reason: String) {
            runOnUiThread {
                Toast.makeText(this@GameActivity, reason, Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()

        ai = intent.getBooleanExtra("ai", false)
        aiLevel = AiLevel.entries.getOrElse(
            intent.getIntExtra("aiLevel", AiLevel.MEDIUM.ordinal)
        ) { AiLevel.MEDIUM }
        practice = !ai && intent.getIntExtra("player", 0) == -1
        localPlayer = if (practice) -1 else if (ai) 0 else intent.getIntExtra("player", 0)
        world = GameWorld(practice)
        val tableTheme = TableTheme.fromKey(
            getSharedPreferences(TableTheme.PREFS, MODE_PRIVATE)
                .getString(TableTheme.KEY, null)
        )
        val tableTexture = TableTexture.fromKey(
            getSharedPreferences(TableTexture.PREFS, MODE_PRIVATE)
                .getString(TableTexture.KEY, null)
        )
        val textureQuality = TextureQuality.fromKey(
            getSharedPreferences(TextureQuality.PREFS, MODE_PRIVATE)
                .getString(TextureQuality.KEY, null)
        )

        renderer = GameRenderer(
            applicationContext,
            world,
            mirrored = localPlayer == 1,
            theme = tableTheme,
            textureStyle = tableTexture,
            textureQuality = textureQuality
        )
        renderer.localPlayer = localPlayer

        surface = GLSurfaceView(this).apply {
            setEGLContextClientVersion(3)
            setEGLConfigChooser(8, 8, 8, 8, 24, 0)
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }

        hud = HudView(
            this,
            { hudState() },
            object : HudView.Actions {
                override fun onAimDelta(deltaPixels: Float) {
                    surface.queueEvent { renderer.rotateAim(deltaPixels) }
                }

                override fun onShoot() = requestShot()
                override fun onExit() = closeAndFinish()
                override fun onCamera() {
                    surface.queueEvent { renderer.cycleCamera() }
                }

                override fun onTrajectoryToggle(enabled: Boolean) {
                    renderer.showTrajectory = enabled
                }

                override fun onShotParams(power: Float, strikeX: Float, strikeY: Float) {
                    renderer.setShotParams(power, strikeX, strikeY)
                }
            }
        )

        val root = FrameLayout(this)
        root.addView(surface, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(hud, FrameLayout.LayoutParams(MATCH, MATCH))
        setContentView(root)

        if (ai) {
            started = true
            aiPlayer = AiPlayer(world, 1, aiLevel) { shot ->
                surface.queueEvent {
                    world.shoot(shot.angle, shot.power, 0.0, 0.0)
                }
            }
        }

        val service = Session.service
        if (!practice && !ai) {
            started = false
            if (service == null) {
                Toast.makeText(this, "Conexão Bluetooth não encontrada.", Toast.LENGTH_LONG).show()
                finish()
                return
            }
            service.listener = gameListener
            if (localPlayer == 0) {
                started = true
                handler.postDelayed({
                    val current = Session.service ?: return@postDelayed
                    current.send(Wire.start(1))
                    val snapshot = Wire.state(world.snapshot())
                    lastSent = snapshot
                    current.send(snapshot)
                }, 250)
            }
        }
        handler.postDelayed(sync, 200)
    }

    private fun requestShot() {
        if (!started || !world.canShoot(localPlayer)) return
        var angle = 0.0
        synchronized(world) { angle = world.aimAngle }
        val power = hud.power.toDouble()
        val strikeX = hud.strikeX.toDouble()
        val strikeY = hud.strikeY.toDouble()
        when {
            practice -> surface.queueEvent { world.shoot(angle, power, strikeX, strikeY) }
            localPlayer == 0 -> surface.queueEvent { world.shoot(angle, power, strikeX, strikeY) }
            else -> {
                Session.service?.send(Wire.shot(angle, power, strikeX, strikeY))
                surface.queueEvent { world.shoot(angle, power, strikeX, strikeY) }
            }
        }
    }

    private fun hudState(): HudState {
        val snapshot = world.snapshot()
        return HudState(
            turn = snapshot.turn,
            localPlayer = localPlayer,
            practice = practice,
            potted0 = snapshot.potted0,
            potted1 = snapshot.potted1,
            winner = snapshot.winner,
            moving = snapshot.moving,
            message = snapshot.message,
            started = started,
            canShoot = started && world.canShoot(localPlayer),
            cameraMode = renderer.cameraMode,
            aimAngle = ((Math.toDegrees(world.aimAngle) % 360.0 + 360.0) % 360.0).toFloat(),
            aiThinking = aiPlayer?.thinking == true,
            aiLevel = if (ai) aiLevel.label else ""
        )
    }

    private fun closeAndFinish() {
        if (!closed) {
            closed = true
            handler.removeCallbacks(sync)
            Session.service?.let {
                it.send(Wire.bye())
                it.listener = null
                it.close()
            }
            Session.service = null
        }
        finish()
    }

    override fun onResume() {
        super.onResume()
        surface.onResume()
        hideSystemBars()
    }

    override fun onPause() {
        super.onPause()
        surface.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        renderer.release()
        aiPlayer?.release()
        handler.removeCallbacks(sync)
        if (!closed) {
            Session.service?.let {
                it.listener = null
                it.close()
            }
            Session.service = null
        }
    }

    private fun hideSystemBars() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            )
    }

    private abstract class BluetoothGameListener : com.ldmaster.sinuca.net.BluetoothGame.Listener {
        override fun onConnected(peer: String) = Unit
    }

    companion object {
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    }
}
