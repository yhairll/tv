package com.canales.simple

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.tv.TvContract
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

    private val channels = mutableListOf<Ch>()
    private var index = 0
    private val handler = Handler(Looper.getMainLooper())
    private val hideBox = Runnable { box.animate().alpha(0f).setDuration(350).start() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        buildUi()
        val error = loadChannels()
        if (channels.isEmpty()) {
            msgText.visibility = View.VISIBLE
            msgText.text = "No se pudieron leer los canales de la TV.\n" +
                (error ?: "El sistema no permite a esta app ver la lista (permiso READ_TV_LISTINGS).")
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
        }
        root.addView(box, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER_VERTICAL or Gravity.START))

        msgText = TextView(this).apply {
            textSize = 24f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            visibility = View.GONE
            setPadding(dp(80), 0, dp(80), 0)
        }
        root.addView(msgText, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        setContentView(root)
    }

    private fun showBox() {
        val c = channels[index]
        numText.text = c.number
        nameText.text = c.name
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
        try {
            contentResolver.query(TvContract.Channels.CONTENT_URI, proj, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val service = c.getString(4)
                    if (service != null && service != TvContract.Channels.SERVICE_TYPE_AUDIO_VIDEO) continue
                    channels.add(Ch(c.getLong(0), c.getString(1) ?: "", c.getString(2) ?: "", c.getString(3) ?: ""))
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
        tvView.tune(c.inputId, TvContract.buildChannelUri(c.id))
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
