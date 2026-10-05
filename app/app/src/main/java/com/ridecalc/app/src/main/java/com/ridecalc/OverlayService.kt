package com.ridecalc

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs
import kotlin.math.roundToInt

class OverlayService : Service() {
    private lateinit var wm: WindowManager
    private lateinit var prefs: SharedPreferences
    private var bubble: View? = null
    private var panel: View? = null
    private var dark = false

    private val bg get() = if (dark) 0xFF1A1D24.toInt() else Color.WHITE
    private val fg get() = if (dark) 0xFFF1F3F6.toInt() else 0xFF14181F.toInt()
    private val mut get() = if (dark) 0xFF9AA3B2.toInt() else 0xFF6B7280.toInt()
    private val ok get() = if (dark) 0xFF4ADE80.toInt() else 0xFF16A34A.toInt()
    private val bad get() = if (dark) 0xFFF87171.toInt() else 0xFFDC2626.toInt()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        prefs = getSharedPreferences("rc", MODE_PRIVATE)
        dark = prefs.getBoolean("dark", false)
        startFg()
        showBubble()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onDestroy() {
        removeBubble(); removePanel()
        super.onDestroy()
    }

    private fun startFg() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel("rc", "Ride Calc", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "rc")
            .setContentTitle("Ride Calc chal raha hai")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, n)
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun params(focus: Boolean, w: Int, h: Int) = WindowManager.LayoutParams(
        w, h, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        if (focus) WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
    }

    private fun removeBubble() { bubble?.let { try { wm.removeView(it) } catch (_: Exception) {} }; bubble = null }
    private fun removePanel() { panel?.let { try { wm.removeView(it) } catch (_: Exception) {} }; panel = null }

    // ---------- Floating bubble (minimized state) ----------
    private fun showBubble() {
        removeBubble()
        val size = dp(54)
        val p = params(false, size, size).apply { x = dp(8); y = dp(220) }
        val tv = TextView(this).apply {
            text = "₨"; gravity = Gravity.CENTER; textSize = 22f; setTextColor(Color.WHITE)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFF2563EB.toInt()) }
        }
        tv.setOnTouchListener(object : View.OnTouchListener {
            var sx = 0; var sy = 0; var tx = 0f; var ty = 0f; var moved = false
            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> { sx = p.x; sy = p.y; tx = e.rawX; ty = e.rawY; moved = false }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (e.rawX - tx).toInt(); val dy = (e.rawY - ty).toInt()
                        if (abs(dx) > 10 || abs(dy) > 10) moved = true
                        p.x = sx + dx; p.y = sy + dy
                        wm.updateViewLayout(v, p)
                    }
                    MotionEvent.ACTION_UP -> if (!moved) v.post { showPanel() }
                }
                return true
            }
        })
        wm.addView(tv, p)
        bubble = tv
    }

    // ---------- Calculator panel ----------
    private fun showPanel(keep: List<String> = listOf("", "", "")) {
        removeBubble(); removePanel()
        val ctx = this

        fun field(hint: String, init: String) = EditText(ctx).apply {
            this.hint = hint; setText(init)
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setTextColor(fg); setHintTextColor(mut); textSize = 17f; setSingleLine()
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        fun label(t: String) = TextView(ctx).apply {
            text = t; setTextColor(mut); textSize = 13f
            layoutParams = LinearLayout.LayoutParams(dp(62), LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        fun chip(t: String, color: Int? = null, onClick: () -> Unit) = TextView(ctx).apply {
            text = t; textSize = 16f; setPadding(dp(12), dp(8), dp(12), dp(8))
            setTextColor(if (color != null) Color.WHITE else fg)
            if (color != null) background = GradientDrawable().apply { setColor(color); cornerRadius = dp(10).toFloat() }
            setOnClickListener { onClick() }
        }
        fun row(vararg v: View) = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            v.forEach { addView(it) }
        }

        val pp = field("Rs/L", prefs.getString("pp", "") ?: "")
        val av = field("km/L", prefs.getString("av", "") ?: "")
        val fa = field("Rs", keep[0])
        val pk = field("km", keep[1])
        val rd = field("km", keep[2])
        val res = TextView(ctx).apply {
            gravity = Gravity.CENTER; textSize = 24f; setTextColor(mut)
            text = "Values daalein"; setPadding(0, dp(8), 0, 0)
        }

        fun n(e: EditText) = e.text.toString().toDoubleOrNull() ?: 0.0
        fun calc() {
            val p = n(pp); val a = n(av); val f = n(fa); val km = n(pk) + n(rd)
            if (p <= 0 || a <= 0 || f <= 0 || km <= 0) {
                res.setTextColor(mut); res.text = "Values daalein"; return
            }
            val cost = km / a * p
            val pr = f - cost
            res.setTextColor(if (pr >= 0) ok else bad)
            res.text = (if (pr >= 0) "PROFIT Rs " else "LOSS Rs ") + abs(pr).roundToInt() +
                "\nPetrol Rs ${cost.roundToInt()}  •  ${"%.1f".format(pr / km)}/km"
        }
        val tw = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) = calc()
        }
        listOf(pp, av, fa, pk, rd).forEach { it.addTextChangedListener(tw) }

        fun save(key: String, e: EditText) = chip("Save", 0xFF2563EB.toInt()) {
            if (e.text.isNotBlank()) {
                prefs.edit().putString(key, e.text.toString()).apply()
                Toast.makeText(ctx, "Save ho gaya ✓", Toast.LENGTH_SHORT).show()
            }
        }

        val title = TextView(ctx).apply {
            text = "🏍️ Ride Profit"; setTextColor(fg); textSize = 17f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val themeBtn = chip("🌓") {
            val k = listOf(fa.text.toString(), pk.text.toString(), rd.text.toString())
            dark = !dark
            prefs.edit().putBoolean("dark", dark).apply()
            showPanel(k)
        }
        val minBtn = chip("—") { removePanel(); showBubble() }
        val closeBtn = chip("✕") { stopSelf() }

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(12))
            background = GradientDrawable().apply {
                setColor(bg); cornerRadius = dp(16).toFloat(); setStroke(dp(1), mut)
            }
            addView(row(title, themeBtn, minBtn, closeBtn))
            addView(row(label("Petrol"), pp, save("pp", pp)))
            addView(row(label("Average"), av, save("av", av)))
            addView(row(label("Fare"), fa))
            addView(row(label("Pick-up"), pk, label("  Ride"), rd))
            addView(res)
        }

        val w = (resources.displayMetrics.widthPixels * 0.94).toInt()
        val p = params(true, w, WindowManager.LayoutParams.WRAP_CONTENT).apply {
            x = (resources.displayMetrics.widthPixels - w) / 2; y = dp(40)
        }
        wm.addView(root, p)
        panel = root
        calc()
    }
}
