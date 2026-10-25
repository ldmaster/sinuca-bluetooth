package com.ldmaster.sinuca

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.graphics.drawable.ColorDrawable
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
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
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
    private lateinit var badgesLine: TextView
    private lateinit var statWins: TextView
    private lateinit var statLosses: TextView
    private lateinit var statStreak: TextView
    private lateinit var aiModeSubtitle: TextView
    private lateinit var aiDescription: TextView
    private lateinit var levelChips: LinearLayout
    private var themeValue: TextView? = null
    private var textureValue: TextView? = null
    private var qualityValue: TextView? = null
    private lateinit var backgroundImage: ImageView
    private lateinit var carousel: HorizontalScrollView
    private lateinit var carouselRow: LinearLayout
    private lateinit var dotsRow: LinearLayout
    private val modeCards = ArrayList<View>()
    private val modeDots = ArrayList<View>()
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
        val root = FrameLayout(this)

        backgroundImage = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        root.addView(backgroundImage, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        val scrim = View(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(
                    Color.parseColor("#F2070B12"),
                    Color.parseColor("#E6070B12"),
                    Color.parseColor("#55070B12")
                )
            )
        }
        root.addView(scrim, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(30), dp(16), dp(24), dp(14))
        }
        root.addView(column, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        column.addView(headerRow(), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        column.addView(
            text("ESCOLHA O MODO", 10f, "#6C7A8C", bold = true).apply {
                letterSpacing = 0.16f
                setPadding(0, dp(14), 0, 0)
            }
        )
        column.addView(
            text("arraste para o lado", 10f, "#4E5C6E")
                .apply { setPadding(0, dp(2), 0, dp(8)) }
        )
        column.addView(buildCarousel(), LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        dotsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        column.addView(
            dotsRow,
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(6) }
        )
        buildDots()
        setupCarousel()
        carousel.post {
            carousel.scrollTo(0, 0)
            updateCarouselState(0)
        }

        refreshLevelUi()
        refreshStats()
        refreshSettings()
        return root
    }

    private fun headerRow(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val brand = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        brand.addView(text("SINUCA", 34f, "#F2F6FA", bold = true))
        brand.addView(text("Mesa de 9 pés · física própria · 4 temas", 11f, "#7FD1A6"))
        row.addView(brand, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        row.addView(statsStrip())
        row.addView(
            settingsButton(),
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(12) }
        )
        return row
    }

    private fun statsStrip(): View {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
            setPadding(0, 0, dp(4), 0)
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        statWins = statPill(row, "VITÓRIAS")
        statLosses = statPill(row, "DERROTAS")
        statStreak = statPill(row, "SEQUÊNCIA")
        column.addView(row)
        badgesLine = text("", 9f, "#8FA0B4").apply {
            gravity = Gravity.END
            setPadding(0, dp(6), 0, 0)
        }
        column.addView(badgesLine)
        return column
    }

    private fun statPill(parent: LinearLayout, label: String): TextView {
        val cell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = rounded("#CC121A26", "#1E2C3E", radius = 14)
            setPadding(dp(16), dp(6), dp(16), dp(6))
        }
        val value = text("0", 20f, "#F2F6FA", bold = true)
        cell.addView(value)
        cell.addView(text(label, 8f, "#66758A").apply { letterSpacing = 0.1f })
        parent.addView(
            cell,
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(8) }
        )
        return value
    }

    private fun settingsButton(): View = text("AJUSTES ›", 11f, "#E6F2F6FA", bold = true).apply {
        gravity = Gravity.CENTER
        setPadding(dp(14), dp(12), dp(14), dp(12))
        background = rounded("#CC1B2534", "#2A3A50", radius = 14)
        isClickable = true
        setOnClickListener { openSettings() }
    }

    private fun buildCarousel(): View {
        carousel = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            clipToPadding = false
            setPadding(0, 0, dp(48), 0)
        }
        carouselRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        carousel.addView(
            carouselRow,
            ViewGroup.LayoutParams(WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        val cardWidth = (resources.displayMetrics.widthPixels * 0.40f).toInt()
        addModeCard(treinoCard(), cardWidth)
        addModeCard(aiCard(), cardWidth)
        addModeCard(multiplayerCard(), cardWidth)
        return carousel
    }

    private fun chevronRow(label: String, action: () -> Unit): Pair<View, TextView> {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(10))
            isClickable = true
            setOnClickListener { action() }
        }
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(text(label, 9f, "#66758A", bold = true).apply { letterSpacing = 0.14f })
        val value = text("", 14f, "#E6F2F6FA")
        column.addView(value)
        row.addView(column, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        row.addView(text("›", 20f, "#4E5C6E"))
        return row to value
    }

    private fun addModeCard(card: View, width: Int) {
        modeCards.add(card)
        carouselRow.addView(
            card,
            LinearLayout.LayoutParams(width, MATCH_PARENT).apply {
                marginEnd = dp(14)
                topMargin = dp(2)
                bottomMargin = dp(2)
            }
        )
    }

    private fun buildDots() {
        modeDots.clear()
        dotsRow.removeAllViews()
        for (index in modeCards.indices) {
            val dot = View(this).apply {
                background = rounded("#2A3A50", "#2A3A50", radius = 4)
                setOnClickListener {
                    carousel.smoothScrollTo(modeCards[index].left, 0)
                }
            }
            dotsRow.addView(
                dot,
                LinearLayout.LayoutParams(dp(8), dp(8)).apply { marginEnd = dp(7) }
            )
            modeDots.add(dot)
        }
        updateCarouselState(0)
    }

    private fun setupCarousel() {
        carousel.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP ||
                event.action == MotionEvent.ACTION_CANCEL
            ) {
                carousel.post { snapToNearestCard() }
            }
            false
        }
        carousel.setOnScrollChangeListener { _, scrollX, _, _, _ ->
            updateCarouselState(scrollX)
        }
    }

    private fun snapToNearestCard() {
        var bestIndex = 0
        var bestDistance = Int.MAX_VALUE
        for (index in modeCards.indices) {
            val distance = kotlin.math.abs(modeCards[index].left - carousel.scrollX)
            if (distance < bestDistance) {
                bestDistance = distance
                bestIndex = index
            }
        }
        carousel.smoothScrollTo(modeCards[bestIndex].left, 0)
    }

    private fun updateCarouselState(scrollX: Int) {
        var centered = 0
        var bestDistance = Int.MAX_VALUE
        for (index in modeCards.indices) {
            val card = modeCards[index]
            if (card.width == 0) return
            val distance = kotlin.math.abs(card.left - scrollX)
            if (distance < bestDistance) {
                bestDistance = distance
                centered = index
            }
            card.alpha = if (distance < card.width * 0.35f) 1f else 0.5f
        }
        for (index in modeDots.indices) {
            modeDots[index].background = rounded(
                if (index == centered) "#8B5CF6" else "#2A3A50",
                if (index == centered) "#8B5CF6" else "#2A3A50",
                radius = 4
            )
        }
    }

    private fun cardShell(kicker: String, title: String, accent: String): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded("#F0121A26", accent, radius = 22)
            setPadding(dp(20), dp(18), dp(20), dp(18))
        }
        card.addView(text(kicker, 10f, accent, bold = true).apply { letterSpacing = 0.18f })
        card.addView(text(title, 26f, "#F2F6FA", bold = true))
        return card
    }

    private fun treinoCard(): LinearLayout {
        val card = cardShell("SOLO", "Treino livre", "#2F6F8F")
        card.addView(
            text("Sem pressa e sem placar: a mesa é sua.", 12f, "#9AA8B8")
                .apply { setPadding(0, dp(6), 0, dp(12)) }
        )
        card.addView(View(this), LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        card.addView(button("JOGAR", "#2F6F8F") { practice() })
        return card
    }

    private fun aiCard(): LinearLayout {
        val card = cardShell("1 VS IA", "Contra a IA", "#8B5CF6")
        aiModeSubtitle = text("", 12f, "#C7B6F5").apply { setPadding(0, dp(4), 0, dp(10)) }
        card.addView(aiModeSubtitle)
        card.addView(levelChipRow())
        aiDescription = text("", 11f, "#8FA0B4").apply { setPadding(0, dp(10), 0, dp(8)) }
        card.addView(aiDescription)
        card.addView(View(this), LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        card.addView(button("JOGAR", "#8B5CF6") { aiGame() })
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
        val card = cardShell("2 JOGADORES", "Multiplayer", "#3E8E5A")
        card.addView(
            text(
                "Dois celulares com Bluetooth pareado. O host fica visível e o convidado entra.",
                11f,
                "#9AA8B8"
            ).apply { setPadding(0, dp(6), 0, dp(6)) }
        )
        card.addView(button("Criar partida (host)", "#1F6F4A") { hostGame() })
        card.addView(button("Entrar em partida", "#1B4F72") { joinGame() })
        status = text("Pronto.", 12f, "#C7D2DE").apply {
            setPadding(0, dp(10), 0, dp(4))
        }
        card.addView(status)
        val deviceScroll = ScrollView(this)
        devices = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        deviceScroll.addView(devices)
        card.addView(deviceScroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        return card
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
        val badges = AiLevel.entries.joinToString("    ") { level ->
            val mark = if (hallStats.beaten(level)) "●" else "○"
            "$mark ${level.label}"
        }
        badgesLine.text = if (hallStats.wins + hallStats.losses == 0) {
            badges
        } else {
            "$badges    ·    melhor seq. ${hallStats.bestStreak}"
        }
    }

    private fun refreshSettings() {
        themeValue?.text = tableTheme.label
        textureValue?.text = tableTexture.label
        qualityValue?.text = tableQuality.label
        refreshBackground()
    }

    private fun refreshBackground() {
        val index = TableTexture.entries.indexOf(tableTexture) + 1
        runCatching {
            assets.open("previews/p${index}_${tableTheme.key}.jpg").use { stream ->
                backgroundImage.setImageDrawable(Drawable.createFromStream(stream, null))
            }
        }
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
        val dialog = Dialog(this)
        val panel = dialogPanel(
            "Tema da mesa",
            "Madeira, metal e pano. A miniatura usa a textura atual."
        )
        val options = addScrollingOptions(panel, 280)
        for (theme in TableTheme.entries) {
            options.addView(
                optionRow(
                    title = theme.label,
                    subtitle = theme.description,
                    selected = theme == tableTheme,
                    thumbnail = previewPath(theme, tableTexture)
                ) {
                    tableTheme = theme
                    getSharedPreferences(TableTheme.PREFS, MODE_PRIVATE)
                        .edit()
                        .putString(TableTheme.KEY, theme.key)
                        .apply()
                    refreshSettings()
                    dialog.dismiss()
                },
                LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
                    .apply { topMargin = dp(8) }
            )
        }
        panel.addView(button("Fechar", "#1B2534") { dialog.dismiss() })
        showPanel(dialog, panel, widthFraction = 0.56f)
    }

    private fun chooseTexture() {
        val dialog = Dialog(this)
        val panel = dialogPanel(
            "Texturas 4K",
            "Toque numa miniatura para aplicar tema + textura."
        )
        val options = addScrollingOptions(panel, 300)
        for (theme in TableTheme.entries) {
            options.addView(
                text(theme.label, 12f, "#7FD1A6", bold = true)
                    .apply { setPadding(0, dp(12), 0, dp(6)) }
            )
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for ((index, texture) in TableTexture.entries.withIndex()) {
                val selected = theme == tableTheme && texture == tableTexture
                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    background = rounded(
                        if (selected) "#1B2436" else "#0F1724",
                        if (selected) "#8B5CF6" else "#1E2C3E",
                        radius = 14
                    )
                    setPadding(dp(4), dp(4), dp(4), dp(6))
                    isClickable = true
                    setOnClickListener {
                        selectLook(theme, texture)
                        dialog.dismiss()
                    }
                }
                val image = ImageView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(58))
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    background = rounded("#0B121C", "#1E2C3E", radius = 9)
                    clipToOutline = true
                }
                runCatching {
                    assets.open("previews/p${index + 1}_${theme.key}.jpg").use { stream ->
                        image.setImageDrawable(Drawable.createFromStream(stream, null))
                    }
                }
                card.addView(image)
                card.addView(
                    text(texture.label, 8.5f, if (selected) "#E6F2F6FA" else "#8FA0B4")
                        .apply { setPadding(dp(2), dp(4), 0, 0) }
                )
                row.addView(
                    card,
                    LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
                        .apply { marginEnd = dp(6) }
                )
            }
            options.addView(row)
        }
        panel.addView(button("Fechar", "#1B2534") { dialog.dismiss() })
        showPanel(dialog, panel, widthFraction = 0.82f)
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
        val dialog = Dialog(this)
        val panel = dialogPanel(
            "Qualidade da textura",
            "Mais detalhe custa memória e frames."
        )
        val options = addScrollingOptions(panel, 280)
        for (quality in TextureQuality.entries) {
            options.addView(
                optionRow(
                    title = quality.label,
                    subtitle = quality.description,
                    selected = quality == tableQuality,
                    thumbnail = null
                ) {
                    tableQuality = quality
                    getSharedPreferences(TextureQuality.PREFS, MODE_PRIVATE)
                        .edit()
                        .putString(TextureQuality.KEY, quality.key)
                        .apply()
                    refreshSettings()
                    dialog.dismiss()
                },
                LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
                    .apply { topMargin = dp(8) }
            )
        }
        panel.addView(button("Fechar", "#1B2534") { dialog.dismiss() })
        showPanel(dialog, panel, widthFraction = 0.50f)
    }

    private fun openSettings() {
        val dialog = Dialog(this)
        val panel = dialogPanel(
            "Ajustes da mesa",
            "Toque para trocar; a prévia do fundo muda na hora."
        )
        val themeRow = chevronRow("TEMA") {
            dialog.dismiss()
            chooseTheme()
        }
        themeValue = themeRow.second
        panel.addView(themeRow.first)
        val textureRow = chevronRow("TEXTURA 4K") {
            dialog.dismiss()
            chooseTexture()
        }
        textureValue = textureRow.second
        panel.addView(textureRow.first)
        val qualityRow = chevronRow("QUALIDADE") {
            dialog.dismiss()
            chooseQuality()
        }
        qualityValue = qualityRow.second
        panel.addView(qualityRow.first)
        refreshSettings()
        panel.addView(button("Fechar", "#1B2534") { dialog.dismiss() })
        showPanel(dialog, panel, widthFraction = 0.46f)
    }

    /** Fixed-height option list; the panel's own close button stays visible. */
    private fun addScrollingOptions(panel: LinearLayout, heightDp: Int): LinearLayout {
        val options = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(2), dp(2), dp(2))
        }
        val scroll = ScrollView(this).apply { isFillViewport = false }
        scroll.addView(options)
        panel.addView(
            scroll,
            LinearLayout.LayoutParams(MATCH_PARENT, dp(heightDp))
        )
        return options
    }

    private fun dialogPanel(title: String, subtitle: String): LinearLayout {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded("#0E1622", "#26384F", radius = 24)
            setPadding(dp(18), dp(18), dp(18), dp(16))
        }
        panel.addView(text(title, 20f, "#F2F6FA", bold = true))
        panel.addView(
            text(subtitle, 11f, "#8FA0B4").apply { setPadding(0, dp(2), 0, dp(6)) }
        )
        return panel
    }

    private fun showPanel(dialog: Dialog, content: View, widthFraction: Float) {
        dialog.setContentView(content)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            val width = (resources.displayMetrics.widthPixels * widthFraction).toInt()
            setLayout(width, WRAP_CONTENT)
        }
        dialog.show()
    }

    private fun optionRow(
        title: String,
        subtitle: String,
        selected: Boolean,
        thumbnail: String?,
        action: () -> Unit
    ): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(
                if (selected) "#1B2436" else "#0F1724",
                if (selected) "#8B5CF6" else "#1E2C3E",
                radius = 16
            )
            setPadding(dp(10), dp(10), dp(14), dp(10))
            isClickable = true
            setOnClickListener { action() }
        }
        if (thumbnail != null) {
            val image = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(92), dp(68))
                    .apply { marginEnd = dp(12) }
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = rounded("#0B121C", "#1E2C3E", radius = 10)
                clipToOutline = true
            }
            runCatching {
                assets.open(thumbnail).use { stream ->
                    image.setImageDrawable(Drawable.createFromStream(stream, null))
                }
            }
            row.addView(image)
        }
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(text(title, 15f, "#F2F6FA", bold = true))
        column.addView(text(subtitle, 11f, "#8FA0B4"))
        row.addView(column, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        row.addView(
            text(
                if (selected) "●" else "○",
                14f,
                if (selected) "#8B5CF6" else "#3A4A60"
            )
        )
        return row
    }

    private fun previewPath(theme: TableTheme, texture: TableTexture): String {
        val index = TableTexture.entries.indexOf(texture) + 1
        return "previews/p${index}_${theme.key}.jpg"
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
