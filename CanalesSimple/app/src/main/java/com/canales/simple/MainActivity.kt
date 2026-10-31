package com.canales.simple

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.tv.TvContentRating
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import android.media.tv.TvView
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {

    private data class Ch(val id: Long, val number: String, val name: String, val inputId: String)

    private lateinit var tvView: TvView
    private lateinit var box: LinearLayout
    private lateinit var numText: TextView
    private lateinit var nameText: TextView
    private lateinit var msgText: TextView
    private lateinit var inputText: TextView
    private val perInput = HashMap<String, Int>()
    private var inputsInfo = ""

    private val channels = mutableListOf<Ch>()
    private var index = 0
    private var rawCount = 0
    private var sample = ""
    private val handler = Handler(Looper.getMainLooper())
    private val hideBox = Runnable { box.animate().alpha(0f).setDuration(350).start() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        buildUi()
        tvView.setCallback(object : TvView.TvInputCallback() {
            override fun onConnectionFailed(inputId: String?) {
                runOnUiThread { showMsg("No se pudo conectar con el sintonizador ($inputId)") }
            }
            override fun onVideoAvailable(inputId: String?) {
                runOnUiThread {
                    msgText.visibility = View.GONE
                    tvView.setStreamVolume(1.0f)
                }
            }
            override fun onContentBlocked(inputId: String?, rating: TvContentRating?) {
                runOnUiThread { showMsg("Canal bloqueado por control parental ($rating)") }
            }
            override fun onVideoUnavailable(inputId: String?, reason: Int) {
                val txt = when (reason) {
                    TvInputManager.VIDEO_UNAVAILABLE_REASON_TUNING -> null
                    TvInputManager.VIDEO_UNAVAILABLE_REASON_BUFFERING -> null
                    TvInputManager.VIDEO_UNAVAILABLE_REASON_WEAK_SIGNAL -> "Señal débil o sin señal en este canal"
                    TvInputManager.VIDEO_UNAVAILABLE_REASON_AUDIO_ONLY -> "Este canal solo tiene audio"
                    else -> "Sin imagen en este canal (motivo $reason)"
                }
                if (txt != null) runOnUiThread { showMsg(txt) }
            }
        })
        val error = loadChannels()
        if (channels.isEmpty()) {
            showMsg("No se pudieron leer canales válidos de la TV.\n" +
                "Filas leídas: $rawCount\n" +
                "Canales por fuente: $perInput\n" +
                "Sintonizadores del sistema:\n$inputsInfo\n" +
                (if (sample.isNotEmpty()) "Primera fila: $sample\n" else "") +
                (error ?: "Si es 0, el sistema no permite ver la lista (permiso READ_TV_LISTINGS)."))
            return
        }
        index = getPreferences(MODE_PRIVATE).getInt("last", 0).coerceIn(0, channels.size - 1)
    }

    override fun onResume() {
        super.onResume()
        if (channels.isNotEmpty()) tune(index)
    }

    override fun onPause() {
        super.onPause()
        getPreferences(MODE_PRIVATE).edit().putInt("last", index).apply()
        tvView.reset()
    }

    // ---------- Interfaz ----------
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun buildUi() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        tvView = TvView(this)
        tvView.setZOrderMediaOverlay(true)
        root.addView(tvView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        numText = TextView(this).apply {
            textSize = 54f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
        }
        nameText = TextView(this).apply {
            textSize = 26f
            setTextColor(Color.parseColor("#D5D7DC"))
            maxLines = 1
        }

        inputText = TextView(this).apply {
            textSize = 15f
            setTextColor(Color.parseColor("#9AA0A6"))
            maxLines = 1
        }

        box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.START
            minimumWidth = dp(260)
            setPadding(dp(44), dp(26), dp(56), dp(26))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#E0464850"))
                val r = dp(28).toFloat()
                cornerRadii = floatArrayOf(0f, 0f, r, r, r, r, 0f, 0f)
            }
            alpha = 0f
            addView(numText)
            addView(nameText)
            addView(inputText)
        }
        root.addView(box, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER_VERTICAL or Gravity.START))

        msgText = TextView(this).apply {
            textSize = 18f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            visibility = View.GONE
            setPadding(dp(80), 0, dp(80), 0)
        }
        root.addView(msgText, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        setContentView(root)
    }

    private fun showMsg(text: String) {
        msgText.text = text
        msgText.visibility = View.VISIBLE
    }

    private fun showBox() {
        val c = channels[index]
        numText.text = c.number
        nameText.text = c.name
        inputText.text = c.inputId.take(70)
        handler.removeCallbacks(hideBox)
        box.animate().cancel()
        box.alpha = 1f
        handler.postDelayed(hideBox, 3000)
    }

    // ---------- Canales ----------
    private fun loadChannels(): String? {
        var error: String? = null
        val proj = arrayOf(
            TvContract.Channels._ID,
            TvContract.Channels.COLUMN_DISPLAY_NUMBER,
            TvContract.Channels.COLUMN_DISPLAY_NAME,
            TvContract.Channels.COLUMN_INPUT_ID,
            TvContract.Channels.COLUMN_SERVICE_TYPE
        )
        val tim = getSystemService(Context.TV_INPUT_SERVICE) as TvInputManager
        val tunerIds: Set<String> = try {
            tim.tvInputList.filter { it.type == TvInputInfo.TYPE_TUNER }.map { it.id }.toSet()
        } catch (e: Exception) { emptySet() }
        inputsInfo = try {
            tim.tvInputList.joinToString("\n") { "${it.id.take(60)} tipo=${it.type}" }
        } catch (e: Exception) { "(no se pudo listar)" }
        try {
            contentResolver.query(TvContract.Channels.CONTENT_URI, proj, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    rawCount++
                    val service = c.getString(4)
                    val inputId = c.getString(3) ?: ""
                    if (rawCount == 1) {
                        sample = "id=${c.getLong(0)} num=${c.getString(1)} nombre=${c.getString(2)} input='$inputId' tipo=$service"
                    }
                    if (inputId.isEmpty()) continue
                    perInput[inputId] = (perInput[inputId] ?: 0) + 1
                    if (tunerIds.isNotEmpty() && inputId !in tunerIds) continue
                    if (service != null && service != TvContract.Channels.SERVICE_TYPE_AUDIO_VIDEO) continue
                    channels.add(Ch(c.getLong(0), c.getString(1) ?: "", c.getString(2) ?: "", inputId))
                }
            }
        } catch (e: Exception) {
            error = e.message
        }
        channels.sortWith(Comparator { a, b -> compareNumbers(a.number, b.number) })
        return error
    }

    // Ordena "2.1", "2.4", "10.1" correctamente.
    private fun compareNumbers(a: String, b: String): Int {
        val pa = a.split(Regex("[^0-9]+")).filter { it.isNotEmpty() }.map { it.toInt() }
        val pb = b.split(Regex("[^0-9]+")).filter { it.isNotEmpty() }.map { it.toInt() }
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val d = (pa.getOrElse(i) { 0 }).compareTo(pb.getOrElse(i) { 0 })
            if (d != 0) return d
        }
        return 0
    }

    private fun tune(i: Int) {
        if (channels.isEmpty()) return
        index = ((i % channels.size) + channels.size) % channels.size
        val c = channels[index]
        try {
            tvView.tune(c.inputId, TvContract.buildChannelUri(c.id))
            tvView.setStreamVolume(1.0f)
            tvView.setCaptionEnabled(false)
        } catch (e: Exception) {
            showMsg("No se pudo sintonizar este canal:\n${e.message}")
        }
        showBox()
    }

    // ---------- Control remoto ----------
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                tune(index + 1); return true
            }
            KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT -> {
                tune(index - 1); return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_INFO -> {
                if (channels.isNotEmpty()) showBox(); return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }
}
