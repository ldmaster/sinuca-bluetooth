package com.ldmaster.sinuca

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
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
import com.ldmaster.sinuca.game.AiMemory
import com.ldmaster.sinuca.game.PrefsMemoryStore
import com.ldmaster.sinuca.game.TableTexture
import com.ldmaster.sinuca.game.TableTheme
import com.ldmaster.sinuca.game.TextureQuality
import com.ldmaster.sinuca.net.BluetoothGame
import com.ldmaster.sinuca.net.NetMessage
import com.ldmaster.sinuca.net.Session
import com.ldmaster.sinuca.ui.HallStats
import kotlin.math.roundToInt

class MainActivity : Activity() {

    private var adapter: BluetoothAdapter? = null
    private var service: BluetoothGame? = null
    private var pendingAction: (() -> Unit)? = null
    private var afterEnable: (() -> Unit)? = null
    private var connectingAsHost = false
    private var receiverRegistered = false

    private lateinit var status: TextView
    private lateinit var devices: LinearLayout
    private lateinit var statsLine: TextView
    private lateinit var badgesLine: TextView
    private lateinit var statWins: TextView
    private lateinit var statLosses: TextView
    private lateinit var statStreak: TextView
    private lateinit var aiModeSubtitle: TextView
    private lateinit var aiDescription: TextView
    private lateinit var levelChips: LinearLayout
    private lateinit var themeValue: TextView
    private lateinit var textureValue: TextView
    private lateinit var qualityValue: TextView
    private var tableTheme = TableTheme.DEFAULT
    private var tableTexture = TableTexture.DEFAULT
    private var tableQuality = TextureQuality.DEFAULT
    private var aiLevel = AiLevel.MEDIUM
    private val hallStats by lazy { HallStats(this) }
    private val aiMemory by lazy { AiMemory(PrefsMemoryStore(this)) }
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
        aiLevel = AiLevel.entries.firstOrNull {
            it.name == getSharedPreferences(TableTheme.PREFS, MODE_PRIVATE)
                .getString(KEY_AI_LEVEL, null)
        } ?: AiLevel.MEDIUM
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
            setBackgroundColor(Color.parseColor("#070B12"))
            isFillViewport = true
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(26), dp(20), dp(36))
        }
        scroll.addView(column, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        column.addView(text("SINUCA", 40f, "#F2F6FA", bold = true))
        column.addView(text("Mesa de 9 pés · física própria · 4 temas", 13f, "#7FD1A6").apply {
            setPadding(0, dp(2), 0, dp(18))
        })

        column.addView(statsCard())

        column.addView(section("MODOS"))
        val modes = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val practiceCard = modeCard(
            "SOLO",
            "Treino livre",
            "Sem pressa e sem placar",
            "#2F6F8F"
        ) { practice() }
        val aiCard = modeCard(
            "1 vs IA",
            "Contra a IA",
            "",
            "#8B5CF6"
        ) { aiGame() }
        aiModeSubtitle = aiCard.getChildAt(2) as TextView
        modes.addView(
            practiceCard,
            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginEnd = dp(10) }
        )
        modes.addView(aiCard, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        column.addView(modes)

        column.addView(section("DIFICULDADE DA IA"))
        column.addView(levelChipRow())
        aiDescription = text("", 12f, "#8FA0B4").apply { setPadding(0, dp(10), 0, 0) }
        column.addView(aiDescription)

        column.addView(section("MULTIPLAYER"))
        column.addView(multiplayerCard())

        column.addView(section("AJUSTES DA MESA"))
        column.addView(settingsCard())

        column.addView(
            text("Versão ${versionName()} · jogador 1 é você, jogador 2 é o adversário", 10f, "#4E5C6E")
                .apply { setPadding(0, dp(26), 0, 0) }
        )

        refreshLevelUi()
        refreshStats()
        refreshSettings()
        return scroll
    }

    private fun statsCard(): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded("#101825", "#1E2C3E")
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val wins = statCell("VITÓRIAS")
        val losses = statCell("DERROTAS")
        val streak = statCell("SEQUÊNCIA")
        statWins = wins.second
        statLosses = losses.second
        statStreak = streak.second
        row.addView(wins.first, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        row.addView(losses.first, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        row.addView(streak.first, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        card.addView(row)
        badgesLine = text("", 11f, "#8FA0B4").apply { setPadding(0, dp(10), 0, 0) }
        card.addView(badgesLine)
        statsLine = text("", 11f, "#66758A")
        card.addView(statsLine)
        return card
    }

    private fun statCell(label: String): Pair<View, TextView> {
        val cell = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val value = text("0", 26f, "#F2F6FA", bold = true)
        cell.addView(value)
        cell.addView(text(label, 9f, "#66758A").apply { letterSpacing = 0.12f })
        return cell to value
    }

    private fun modeCard(
        kicker: String,
        title: String,
        subtitle: String,
        accent: String,
        action: () -> Unit
    ): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded("#121A26", accent)
            setPadding(dp(16), dp(14), dp(16), dp(16))
            isClickable = true
            isFocusable = true
            setOnClickListener { action() }
        }
        card.addView(text(kicker, 9f, accent, bold = true).apply { letterSpacing = 0.16f })
        card.addView(text(title, 20f, "#F2F6FA", bold = true))
        card.addView(text(subtitle, 11f, "#8FA0B4"))
        return card
    }

    private fun levelChipRow(): View {
        levelChips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (level in AiLevel.entries) {
            val chip = text(level.label, 12f, "#8FA0B4", bold = true).apply {
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(9), dp(8), dp(9))
                isClickable = true
                setOnClickListener { selectAiLevel(level) }
            }
            levelChips.addView(
                chip,
                LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginEnd = dp(6) }
            )
        }
        return levelChips
    }

    private fun multiplayerCard(): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded("#101825", "#1E2C3E")
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        card.addView(
            text(
                "Dois celulares com Bluetooth pareado. O host fica visível e o convidado entra.",
                11f,
                "#8FA0B4"
            )
        )
        card.addView(button("Criar partida (host)", "#1F6F4A") { hostGame() })
        card.addView(button("Entrar em partida", "#1B4F72") { joinGame() })
        status = text("Pronto.", 12f, "#C7D2DE").apply {
            setPadding(0, dp(12), 0, dp(4))
        }
        card.addView(status)
        devices = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        card.addView(devices, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        return card
    }

    private fun settingsCard(): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded("#101825", "#1E2C3E")
            setPadding(dp(16), dp(4), dp(16), dp(4))
        }
        themeValue = settingsRow(card, "TEMA") { chooseTheme() }
        textureValue = settingsRow(card, "TEXTURA") { chooseTexture() }
        qualityValue = settingsRow(card, "QUALIDADE") { chooseQuality() }
        return card
    }

    private fun settingsRow(card: LinearLayout, label: String, action: () -> Unit): TextView {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(12))
            isClickable = true
            setOnClickListener { action() }
        }
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(text(label, 9f, "#66758A", bold = true).apply { letterSpacing = 0.14f })
        val value = text("", 14f, "#E6F2F6FA")
        column.addView(value)
        row.addView(column, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        row.addView(text("›", 22f, "#4E5C6E"))
        card.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        return value
    }

    private fun section(title: String): View =
        text(title, 10f, "#6C7A8C", bold = true).apply {
            letterSpacing = 0.16f
            setPadding(0, dp(24), 0, dp(10))
        }

    private fun rounded(fill: String, stroke: String, radius: Int = 18): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(radius).toFloat()
            setColor(Color.parseColor(fill))
            setStroke(dp(1), Color.parseColor(stroke))
        }

    private fun selectAiLevel(level: AiLevel) {
        aiLevel = level
        getSharedPreferences(TableTheme.PREFS, MODE_PRIVATE)
            .edit()
            .putString(KEY_AI_LEVEL, level.name)
            .apply()
        refreshLevelUi()
    }

    private fun refreshLevelUi() {
        for (index in 0 until levelChips.childCount) {
            val chip = levelChips.getChildAt(index) as TextView
            val level = AiLevel.entries[index]
            val selected = level == aiLevel
            chip.background = rounded(
                if (selected) "#8B5CF6" else "#141C28",
                if (selected) "#8B5CF6" else "#233146",
                radius = 12
            )
            chip.setTextColor(Color.parseColor(if (selected) "#FFFFFF" else "#8FA0B4"))
            chip.setText(if (hallStats.beaten(level)) "${level.label} ·" else level.label)
        }
        aiModeSubtitle.text = "Jogador 2: IA · ${aiLevel.label}"
        val attempts = aiMemory.attempts(aiLevel)
        val learning = when {
            aiLevel.learningWeight <= 0.0 -> "Não acumula aprendizado neste nível."
            attempts == 0 -> "Ainda não aprendeu nenhuma tacada neste nível."
            else -> "Aprendeu com $attempts tacadas · " +
                "${(aiMemory.potRate(aiLevel) * 100).roundToInt()}% de encaixe"
        }
        aiDescription.text = "${aiLevel.tagline}\n$learning"
    }

    private fun refreshStats() {
        statWins.text = hallStats.wins.toString()
        statLosses.text = hallStats.losses.toString()
        statStreak.text = hallStats.streak.toString()
        badgesLine.text = AiLevel.entries.joinToString("    ") { level ->
            val mark = if (hallStats.beaten(level)) "●" else "○"
            "$mark ${level.label}"
        }
        statsLine.text = if (hallStats.wins + hallStats.losses == 0) {
            "Jogue a primeira partida contra a IA para começar o placar."
        } else {
            "Melhor sequência: ${hallStats.bestStreak}"
        }
    }

    private fun refreshSettings() {
        themeValue.text = tableTheme.label
        textureValue.text = tableTexture.label
        qualityValue.text = tableQuality.label
    }

    private fun versionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    } catch (_: Exception) {
        "?"
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
                themeValue.text = tableTheme.label
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
        refreshSettings()
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
                qualityValue.text = tableQuality.label
                dialog.dismiss()
            }
            .show()
    }

    private fun aiGame() {
        service?.close()
        service = null
        Session.service = null
        Session.role = "ai"
        connectingAsHost = false
        startActivity(
            Intent(this, GameActivity::class.java)
                .putExtra("player", 0)
                .putExtra("ai", true)
                .putExtra("aiLevel", aiLevel.ordinal)
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
        private const val KEY_AI_LEVEL = "ai_level"
        private const val MATCH_PARENT = LinearLayout.LayoutParams.MATCH_PARENT
        private const val WRAP_CONTENT = LinearLayout.LayoutParams.WRAP_CONTENT
    }
}
