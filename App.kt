package com.zaso.tasbih

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import java.util.Calendar
import org.json.JSONObject

object Scheduler {
    const val PREFS = "tasbih"
    const val KEY_STATE = "state"
    private const val KEY_CODES = "codes"
    const val CHANNEL = "reminders"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun loadState(ctx: Context): JSONObject {
        val s = prefs(ctx).getString(KEY_STATE, null) ?: return JSONObject()
        return try { JSONObject(s) } catch (e: Exception) { JSONObject() }
    }

    fun saveState(ctx: Context, json: String) {
        prefs(ctx).edit().putString(KEY_STATE, json).apply()
    }

    fun todayKey(): String {
        val c = Calendar.getInstance()
        return String.format("%04d-%02d-%02d", c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
    }

    fun ensureChannel(ctx: Context) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL) == null) {
            val ch = NotificationChannel(CHANNEL, "Tasbih reminders", NotificationManager.IMPORTANCE_HIGH)
            ch.description = "Reminders for your daily tasbih"
            nm.createNotificationChannel(ch)
        }
    }

    private fun parseTime(t: String): Pair<Int, Int>? {
        val m = Regex("^(\\d{1,2}):(\\d{2})$").find(t.trim()) ?: return null
        val h = m.groupValues[1].toInt()
        val min = m.groupValues[2].toInt()
        if (h > 23 || min > 59) return null
        return Pair(h, min)
    }

    private fun pending(ctx: Context, key: String): PendingIntent {
        val i = Intent(ctx, AlarmReceiver::class.java).putExtra("key", key)
        return PendingIntent.getBroadcast(ctx, key.hashCode(), i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun cancelAll(ctx: Context) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val codes = prefs(ctx).getStringSet(KEY_CODES, emptySet()) ?: emptySet()
        for (k in codes) am.cancel(pending(ctx, k))
    }

    private fun scheduleOne(ctx: Context, key: String, time: String) {
        val (h, m) = parseTime(time) ?: return
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, h)
            set(Calendar.MINUTE, m)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (cal.timeInMillis <= System.currentTimeMillis() + 60_000) cal.add(Calendar.DAY_OF_YEAR, 1)
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = pending(ctx, key)
        val exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        if (exact) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pi)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pi)
        }
    }

    fun scheduleAll(ctx: Context) {
        cancelAll(ctx)
        val st = loadState(ctx)
        val codes = mutableSetOf<String>()
        val list = st.optJSONArray("tasbih")
        if (list != null) {
            for (i in 0 until list.length()) {
                val t = list.getJSONObject(i)
                val time = t.optString("time", "")
                if (parseTime(time) != null) {
                    val key = "t:" + t.getString("id")
                    scheduleOne(ctx, key, time)
                    codes.add(key)
                }
            }
        }
        val settings = st.optJSONObject("settings")
        if (settings != null && settings.optBoolean("checkin", false)) {
            val time = settings.optString("checkinTime", "21:30")
            if (parseTime(time) != null) {
                scheduleOne(ctx, "c:checkin", time)
                codes.add("c:checkin")
            }
        }
        prefs(ctx).edit().putStringSet(KEY_CODES, codes).apply()
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val key = intent.getStringExtra("key") ?: return
        // Roll every alarm forward first, so tomorrow's reminder is always set.
        Scheduler.scheduleAll(ctx)
        Scheduler.ensureChannel(ctx)

        val st = Scheduler.loadState(ctx)
        val today = Scheduler.todayKey()
        val dow = Calendar.getInstance().get(Calendar.DAY_OF_WEEK) - 1 // Sunday = 0
        val done = st.optJSONObject("log")?.optJSONObject(today)
        val list = st.optJSONArray("tasbih") ?: return

        val pending = ArrayList<JSONObject>()
        for (i in 0 until list.length()) {
            val t = list.getJSONObject(i)
            if (t.optString("created", "") > today) continue
            val days = t.optJSONArray("days") ?: continue
            var active = false
            for (j in 0 until days.length()) if (days.getInt(j) == dow) active = true
            if (!active) continue
            if (done?.optBoolean(t.getString("id"), false) == true) continue
            pending.add(t)
        }

        val title: String
        val text: String
        if (key.startsWith("t:")) {
            val id = key.substring(2)
            val t = pending.firstOrNull { it.getString("id") == id } ?: return
            title = "Time for " + t.optString("name")
            text = t.optInt("count").toString() + " times. Tap to open."
        } else {
            if (pending.isEmpty()) return
            title = pending.size.toString() + " tasbih still pending today"
            text = pending.joinToString(", ") { it.optString("name") }
        }

        val open = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(ctx, Scheduler.CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(key.hashCode(), n)
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Scheduler.scheduleAll(ctx)
    }
}

class MainActivity : Activity() {
    private lateinit var web: WebView
    private var counterMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Scheduler.ensureChannel(this)
        window.statusBarColor = Color.parseColor("#F5F2EA")
        window.navigationBarColor = Color.parseColor("#FFFFFF")
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR

        web = WebView(this)
        web.setBackgroundColor(Color.parseColor("#F5F2EA"))
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.addJavascriptInterface(Bridge(), "Android")
        setContentView(web)
        web.loadUrl("file:///android_asset/index.html")
        askPermissions()
    }

    override fun onResume() {
        super.onResume()
        Scheduler.scheduleAll(this)
        if (::web.isInitialized) web.evaluateJavascript("window.refresh && window.refresh()", null)
    }

    private fun askPermissions() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        } else {
            askExactAlarm()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        askExactAlarm()
    }

    private fun askExactAlarm() {
        if (Build.VERSION.SDK_INT < 31) return
        val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val p = getSharedPreferences(Scheduler.PREFS, MODE_PRIVATE)
        if (!am.canScheduleExactAlarms() && !p.getBoolean("askedExact", false)) {
            p.edit().putBoolean("askedExact", true).apply()
            try {
                startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
            } catch (e: Exception) {
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (counterMode && keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (event.repeatCount == 0) {
                web.evaluateJavascript("window.onVolumeKey && window.onVolumeKey()", null)
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (counterMode && keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) return true
        return super.onKeyUp(keyCode, event)
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        web.evaluateJavascript("window.handleBack ? window.handleBack() : false") { r ->
            if (r != "true") finish()
        }
    }

    inner class Bridge {
        @JavascriptInterface
        fun loadState(): String =
            getSharedPreferences(Scheduler.PREFS, MODE_PRIVATE).getString(Scheduler.KEY_STATE, "") ?: ""

        @JavascriptInterface
        fun save(json: String) {
            Scheduler.saveState(this@MainActivity, json)
        }

        @JavascriptInterface
        fun sync(json: String) {
            Scheduler.saveState(this@MainActivity, json)
            Scheduler.scheduleAll(this@MainActivity)
        }

        @JavascriptInterface
        fun setCounterMode(on: Boolean) {
            counterMode = on
            runOnUiThread {
                if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }

        @JavascriptInterface
        fun vibrate(ms: Int) {
            @Suppress("DEPRECATION")
            val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            v.vibrate(VibrationEffect.createOneShot(ms.toLong(), VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }
}
