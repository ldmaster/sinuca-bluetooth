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
import com.ldmaster.sinuca.game.GameWorld
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
    private var started = true
    private var lastSent = ""
    private var closed = false

    private val sync = object : Runnable {
        override fun run() {
            val service = Session.service
            if (service != null && started && localPlayer == 0) {
                val encoded = Wire.state(world.snapshot())
                if (encoded != lastSent) {
                    lastSent = encoded
                    service.send(encoded)
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
                            world.shoot(message.angle, message.power)
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

        practice = intent.getIntExtra("player", 0) == -1
        localPlayer = if (practice) -1 else intent.getIntExtra("player", 0)
        world = GameWorld(practice)

        renderer = GameRenderer(applicationContext, world, mirrored = localPlayer == 1)
        renderer.localPlayer = localPlayer

        surface = GLSurfaceView(this).apply {
            setEGLContextClientVersion(3)
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }

        hud = HudView(
            this,
            { hudState() },
            object : HudView.Actions {
                override fun onAim(x: Float, y: Float) {
                    surface.queueEvent { renderer.aimAt(x, y) }
                }

                override fun onShoot() = requestShot()
                override fun onExit() = closeAndFinish()
            }
        )

        val root = FrameLayout(this)
        root.addView(surface, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(hud, FrameLayout.LayoutParams(MATCH, MATCH))
        setContentView(root)

        val service = Session.service
        if (!practice) {
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
        when {
            practice -> surface.queueEvent { world.shoot(angle, power) }
            localPlayer == 0 -> surface.queueEvent { world.shoot(angle, power) }
            else -> {
                Session.service?.send(Wire.shot(angle, power))
                surface.queueEvent { world.shoot(angle, power) }
            }
        }
    }

    private fun hudState(): HudState {
        val snapshot = world.snapshot()
        return HudState(
            turn = snapshot.turn,
            localPlayer = localPlayer,
            practice = practice,
            remaining0 = snapshot.remaining0,
            remaining1 = snapshot.remaining1,
            winner = snapshot.winner,
            moving = snapshot.moving,
            message = snapshot.message,
            started = started,
            canShoot = started && world.canShoot(localPlayer)
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
