package com.ldmaster.sinuca

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.graphics.drawable.Drawable
import android.widget.ImageView
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.ldmaster.sinuca.game.AiLevel
import com.ldmaster.sinuca.game.TableTexture
import com.ldmaster.sinuca.game.TableTheme
import com.ldmaster.sinuca.game.TextureQuality
import com.ldmaster.sinuca.net.BluetoothGame
import com.ldmaster.sinuca.net.NetMessage
import com.ldmaster.sinuca.net.Session

class MainActivity : Activity() {

    private var adapter: BluetoothAdapter? = null
    private var service: BluetoothGame? = null
    private var pendingAction: (() -> Unit)? = null
    private var afterEnable: (() -> Unit)? = null
    private var connectingAsHost = false
    private var receiverRegistered = false

    private lateinit var status: TextView
    private lateinit var devices: LinearLayout
    private lateinit var themeButton: Button
    private lateinit var textureButton: Button
    private lateinit var qualityButton: Button
    private var tableTheme = TableTheme.DEFAULT
    private var tableTexture = TableTexture.DEFAULT
    private var tableQuality = TextureQuality.DEFAULT
    private val found = LinkedHashMap<String, BluetoothDevice>()

    private val menuListener = object : BluetoothGame.Listener {
        override fun onConnected(peer: String) {
            runOnUiThread {
                status.text = "Conectado a $peer"
                startGame(if (connectingAsHost) 0 else 1)
            }
        }

        override fun onMessage(message: NetMessage) = Unit

        override fun onDisconnected(reason: String) {
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                status.text = reason
                service = null
                Session.service = null
            }
        }
    }

    private val discoveryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device = if (Build.VERSION.SDK_INT >= 33) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    }
                    if (device != null) addDevice(device, "encontrado")
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    if (found.isEmpty()) {
                        status.text = "Nenhum aparelho encontrado. Pareie os celulares nas configurações do Android."
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        adapter = BluetoothAdapter.getDefaultAdapter()
        tableTheme = TableTheme.fromKey(
            getSharedPreferences(TableTheme.PREFS, MODE_PRIVATE)
                .getString(TableTheme.KEY, null)
        )
        tableTexture = TableTexture.fromKey(
            getSharedPreferences(TableTexture.PREFS, MODE_PRIVATE)
                .getString(TableTexture.KEY, null)
        )
        tableQuality = TextureQuality.fromKey(
            getSharedPreferences(TextureQuality.PREFS, MODE_PRIVATE)
                .getString(TextureQuality.KEY, null)
        )
        setContentView(buildUi())
        registerDiscovery()
        status.text = if (adapter == null) {
            "Bluetooth indisponível neste aparelho. Use o treino local."
        } else {
            "Pareie os dois celulares nas configurações do Android antes de jogar."
        }
        if (!hasPermissions()) requestPermissions(requiredPermissions(), REQUEST_PERMISSIONS)
    }

    override fun onResume() {
        super.onResume()
        if (Session.service == null && service != null) {
            service?.close()
            service = null
            status.text = "Partida encerrada."
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (receiverRegistered) {
            runCatching { unregisterReceiver(discoveryReceiver) }
            receiverRegistered = false
        }
        if (Session.service == null) {
            service?.close()
            service = null
        }
    }

    private fun buildUi(): View {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#0A0F18"))
            isFillViewport = true
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(28), dp(24), dp(28))
        }
        scroll.addView(column, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        column.addView(text("SINUCA", 36f, "#F2F6FA", bold = true))
        column.addView(text("Bluetooth · 2 jogadores", 16f, "#7FD1A6"))
        column.addView(text("Bolas 1–7 para você, 9–15 para o adversário. A 8 decide.", 14f, "#9AA8B8").apply {
            setPadding(0, dp(10), 0, dp(18))
        })

        column.addView(button("Criar partida (host)", "#1F6F4A") { hostGame() })
        column.addView(button("Entrar em partida", "#1B4F72") { joinGame() })
        column.addView(button("Jogar contra IA", "#2C5F8A") { chooseAiLevel() })
        column.addView(button("Treino local", "#3B3B4A") { practice() })
        themeButton = button("Tema da mesa · ${tableTheme.label}", "#243A52") {
            chooseTheme()
        }
        column.addView(themeButton)
        textureButton = button("Textura 4K · ${tableTexture.label}", "#2B3D2E") {
            chooseTexture()
        }
        column.addView(textureButton)
        qualityButton = button("Qualidade 4K · ${tableQuality.label}", "#3A3226") {
            chooseQuality()
        }
        column.addView(qualityButton)

        status = text("Pronto.", 14f, "#C7D2DE").apply {
            setPadding(0, dp(20), 0, dp(12))
        }
        column.addView(status)

        column.addView(text("DISPOSITIVOS", 12f, "#66758A", bold = true).apply {
            letterSpacing = 0.12f
        })
        column.addView(
            text(
                "O host precisa aceitar ficar visível. Se a conexão falhar, pareie os aparelhos no Android e tente novamente.",
                12f,
                "#66758A"
            ).apply { setPadding(0, dp(6), 0, dp(6)) }
        )

        devices = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(devices, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        column.addView(
            text(
                "Sem Bluetooth? O treino local usa a mesma física e as mesmas regras no mesmo aparelho.",
                12f,
                "#66758A"
            ).apply { setPadding(0, dp(20), 0, 0) }
        )
        return scroll
    }

    private fun hostGame() {
        ensurePermissions {
            ensureEnabled {
                runCatching {
                    startActivityForResult(
                        Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
                            .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300),
                        REQUEST_DISCOVERABLE
                    )
                }
                connectingAsHost = true
                launchService(host = true, device = null)
                status.text = "Aguardando o outro jogador… Deixe este aparelho visível."
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun joinGame() {
        ensurePermissions {
            ensureEnabled {
                devices.removeAllViews()
                found.clear()
                val bonded = try {
                    adapter?.bondedDevices ?: emptySet()
                } catch (error: SecurityException) {
                    emptySet()
                }
                for (device in bonded) addDevice(device, "pareado")
                if (bonded.isEmpty()) {
                    status.text = "Nenhum aparelho pareado. Pareie nas configurações do Android."
                }
                runCatching { adapter?.cancelDiscovery() }
                val started = try {
                    adapter?.startDiscovery() == true
                } catch (error: SecurityException) {
                    false
                }
                status.text = if (started) "Procurando aparelhos Bluetooth…" else "Mostrando aparelhos pareados."
            }
        }
    }

    private fun chooseTheme() {
        val themes = TableTheme.entries
        val labels = themes.map { "${it.label} — ${it.description}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Tema da mesa")
            .setSingleChoiceItems(labels, themes.indexOf(tableTheme)) { dialog, which ->
                tableTheme = themes[which]
                getSharedPreferences(TableTheme.PREFS, MODE_PRIVATE)
                    .edit()
                    .putString(TableTheme.KEY, tableTheme.key)
                    .apply()
                themeButton.text = "Tema da mesa · ${tableTheme.label}"
                dialog.dismiss()
            }
            .show()
    }

    private fun chooseTexture() {
        val dialog = Dialog(this)
        val scroll = ScrollView(this)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
        }
        column.addView(text("Texturas 4K · toque para escolher", 18f, "#F2F6FA", bold = true))
        for (theme in TableTheme.entries) {
            column.addView(
                text(theme.label, 13f, "#7FD1A6", bold = true).apply {
                    setPadding(0, dp(14), 0, dp(6))
                }
            )
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for ((index, texture) in TableTexture.entries.withIndex()) {
                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(4), dp(2), dp(4), dp(6))
                }
                val image = ImageView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(74), dp(52))
                    scaleType = ImageView.ScaleType.CENTER_CROP
                }
                runCatching {
                    assets.open("previews/p${index + 1}_${theme.key}.jpg").use { stream ->
                        image.setImageDrawable(Drawable.createFromStream(stream, null))
                    }
                }
                card.addView(image)
                card.addView(text(texture.label, 8f, "#C7D2DE"))
                card.setOnClickListener {
                    selectLook(theme, texture)
                    dialog.dismiss()
                }
                row.addView(card)
            }
            column.addView(row)
        }
        scroll.addView(column)
        dialog.setContentView(scroll)
        dialog.show()
    }

    private fun selectLook(theme: TableTheme, texture: TableTexture) {
        tableTheme = theme
        tableTexture = texture
        getSharedPreferences(TableTheme.PREFS, MODE_PRIVATE)
            .edit()
            .putString(TableTheme.KEY, theme.key)
            .apply()
        getSharedPreferences(TableTexture.PREFS, MODE_PRIVATE)
            .edit()
            .putString(TableTexture.KEY, texture.key)
            .apply()
        themeButton.text = "Tema da mesa · ${theme.label}"
        textureButton.text = "Textura 4K · ${texture.label}"
    }

    private fun chooseQuality() {
        val qualities = TextureQuality.entries
        val labels = qualities.map { "${it.label} — ${it.description}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Qualidade da textura")
            .setSingleChoiceItems(labels, qualities.indexOf(tableQuality)) { dialog, which ->
                tableQuality = qualities[which]
                getSharedPreferences(TextureQuality.PREFS, MODE_PRIVATE)
                    .edit()
                    .putString(TextureQuality.KEY, tableQuality.key)
                    .apply()
                qualityButton.text = "Qualidade 4K · ${tableQuality.label}"
                dialog.dismiss()
            }
            .show()
    }

    private fun chooseAiLevel() {
        val levels = AiLevel.entries.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Dificuldade da IA")
            .setItems(levels.map { it.label }.toTypedArray()) { _, which ->
                aiGame(levels[which])
            }
            .show()
    }

    private fun aiGame(level: AiLevel) {
        service?.close()
        service = null
        Session.service = null
        Session.role = "ai"
        connectingAsHost = false
        startActivity(
            Intent(this, GameActivity::class.java)
                .putExtra("player", 0)
                .putExtra("ai", true)
                .putExtra("aiLevel", level.ordinal)
        )
    }

    private fun practice() {
        service?.close()
        service = null
        Session.service = null
        Session.role = "practice"
        connectingAsHost = false
        startActivity(Intent(this, GameActivity::class.java).putExtra("player", -1))
    }

    @SuppressLint("MissingPermission")
    private fun connect(device: BluetoothDevice) {
        ensurePermissions {
            ensureEnabled {
                runCatching { adapter?.cancelDiscovery() }
                connectingAsHost = false
                launchService(host = false, device = device)
                status.text = "Conectando a ${deviceLabel(device)}…"
            }
        }
    }

    private fun launchService(host: Boolean, device: BluetoothDevice?) {
        service?.close()
        val game = BluetoothGame()
        game.listener = menuListener
        service = game
        Session.service = game
        Session.role = if (host) "host" else "client"
        if (host) game.startHost() else device?.let { game.startClient(it) }
    }

    private fun startGame(player: Int) {
        Session.role = if (player == 0) "host" else "client"
        startActivity(Intent(this, GameActivity::class.java).putExtra("player", player))
    }

    private fun addDevice(device: BluetoothDevice, tag: String) {
        val address = device.address
        if (found.containsKey(address)) return
        found[address] = device
        val label = deviceLabel(device)
        devices.addView(Button(this).apply {
            text = "$label\n$address · $tag"
            textSize = 14f
            isAllCaps = false
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setTextColor(Color.WHITE)
            backgroundTintList = ColorStateList.valueOf(Color.parseColor("#15202E"))
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(64)).apply { topMargin = dp(8) }
            setOnClickListener { connect(device) }
        })
    }

    private fun deviceLabel(device: BluetoothDevice): String = try {
        device.name ?: "Sem nome"
    } catch (error: SecurityException) {
        "Sem nome"
    }

    private fun ensurePermissions(action: () -> Unit) {
        if (hasPermissions()) {
            action()
        } else {
            pendingAction = action
            requestPermissions(requiredPermissions(), REQUEST_PERMISSIONS)
        }
    }

    @SuppressLint("MissingPermission")
    private fun ensureEnabled(action: () -> Unit) {
        val bluetooth = adapter ?: run {
            toast("Bluetooth indisponível neste aparelho.")
            return
        }
        if (!bluetooth.isEnabled) {
            afterEnable = action
            runCatching {
                startActivityForResult(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), REQUEST_ENABLE)
            }
        } else {
            action()
        }
    }

    private fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= 31) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    private fun hasPermissions(): Boolean = requiredPermissions().all {
        checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
    }

    private fun registerDiscovery() {
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(discoveryReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(discoveryReceiver, filter)
        }
        receiverRegistered = true
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_PERMISSIONS) return
        if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            pendingAction?.invoke()
        } else {
            status.text = "Permissões de Bluetooth são necessárias para o multiplayer."
        }
        pendingAction = null
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQUEST_ENABLE -> {
                val action = afterEnable
                afterEnable = null
                if (resultCode == RESULT_OK) action?.invoke()
                else status.text = "Ative o Bluetooth para jogar em dois aparelhos."
            }
            REQUEST_DISCOVERABLE -> {
                if (resultCode == RESULT_CANCELED) {
                    status.text = "Visibilidade recusada: o outro jogador pode conectar pelo pareamento."
                }
            }
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun text(value: String, size: Float, color: String, bold: Boolean = false): TextView =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(Color.parseColor(color))
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    private fun button(label: String, color: String, action: () -> Unit): Button =
        Button(this).apply {
            text = label
            textSize = 17f
            isAllCaps = false
            setTextColor(Color.WHITE)
            backgroundTintList = ColorStateList.valueOf(Color.parseColor(color))
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(56)).apply { topMargin = dp(12) }
            setOnClickListener { action() }
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val REQUEST_PERMISSIONS = 41
        private const val REQUEST_ENABLE = 42
        private const val REQUEST_DISCOVERABLE = 43
        private const val MATCH_PARENT = LinearLayout.LayoutParams.MATCH_PARENT
        private const val WRAP_CONTENT = LinearLayout.LayoutParams.WRAP_CONTENT
    }
}
