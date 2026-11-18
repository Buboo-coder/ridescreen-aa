// SPDX-License-Identifier: AGPL-3.0-or-later
// Part of RideScreen AA (fork of OpenCfMoto). Free software under the GNU AGPL v3 or later.
package dev.zanderp.opencfmoto

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.MediaController
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.ViewCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Bike camera (DVR): browse the front/rear recordings and pictures kept on the bike, play them and
 * save them to the phone's gallery. Read-only: nothing is ever deleted from the camera. The phone
 * must be on the bike's Wi-Fi (see [DvrClient.findNetwork]); use it while parked.
 */
class DvrActivity : AppCompatActivity() {

    private var videosMode = true
    private var angle = "front"
    private var level = 0                 // videos: 0 = days, 1 = hours of a day, 2 = clips of an hour
    private var selDate: String? = null
    private var selHour: String? = null
    private var net: Network? = null
    private var gen = 0

    private val cmd = Executors.newFixedThreadPool(2)
    private val thumbIo = Executors.newFixedThreadPool(3)
    private val dl = Executors.newCachedThreadPool()
    private val thumbs = LruCache<String, Bitmap>(80)

    private lateinit var status: TextView
    private lateinit var list: LinearLayout
    private lateinit var progress: ProgressBar
    private lateinit var angleRow: LinearLayout
    private lateinit var tabVideos: MaterialButton
    private lateinit var tabPhotos: MaterialButton
    private lateinit var tabFront: MaterialButton
    private lateinit var tabRear: MaterialButton

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun color(id: Int) = ContextCompat.getColor(this, id)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val scroll = ScrollView(this).apply { setBackgroundColor(color(R.color.bg)) }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(12), dp(20), dp(24)) }
        scroll.addView(root)
        root.addView(TextView(this).apply {
            setText(R.string.dvr_title)
            textSize = 24f
            setTextColor(color(R.color.text_primary))
            typeface = ResourcesCompat.getFont(context, R.font.archivo_black)
        })
        status = TextView(this).apply {
            textSize = 14f
            setTextColor(color(R.color.text_secondary))
            setPadding(0, dp(4), 0, dp(10))
        }
        root.addView(status)

        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        tabVideos = tab(R.string.dvr_tab_videos) { if (!videosMode) { videosMode = true; level = 0; load() } }
        tabPhotos = tab(R.string.dvr_tab_photos) { if (videosMode) { videosMode = false; load() } }
        tabs.addView(tabVideos, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) })
        tabs.addView(tabPhotos, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(tabs)

        angleRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        tabFront = tab(R.string.dvr_front) { if (angle != "front") { angle = "front"; level = 0; load() } }
        tabRear = tab(R.string.dvr_rear) { if (angle != "rear") { angle = "rear"; level = 0; load() } }
        angleRow.addView(tabFront, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) })
        angleRow.addView(tabRear, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(angleRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })

        progress = ProgressBar(this)
        root.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(30)
        })
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(list)
        setContentView(scroll)
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { v, insets ->
            val b = insets.getInsets(SafeInsets.BARS)
            v.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (videosMode && level > 0) {
                    level--
                    load()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
        load()
    }

    override fun onDestroy() {
        super.onDestroy()
        gen++
        cmd.shutdownNow()
        thumbIo.shutdownNow()
        // Downloads in progress carry on by themselves (dl pool) with their own network handle.
    }

    // ── loading ─────────────────────────────────────────────────────────────────────────────

    private fun tab(label: Int, onClick: () -> Unit) =
        MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            setText(label)
            isAllCaps = false
            textSize = 14f
            setOnClickListener { onClick() }
        }

    private fun updateTabs() {
        tabVideos.alpha = if (videosMode) 1f else 0.55f
        tabPhotos.alpha = if (videosMode) 0.55f else 1f
        tabFront.alpha = if (angle == "front") 1f else 0.55f
        tabRear.alpha = if (angle == "rear") 1f else 0.55f
        angleRow.visibility = if (videosMode) View.VISIBLE else View.GONE
    }

    private fun ui(my: Int, block: () -> Unit) {
        runOnUiThread { if (my == gen && !isFinishing && !isDestroyed) block() }
    }

    private fun ensureNet(): Network? {
        net?.let { return it }
        val n = DvrClient.findNetwork(this) ?: return null
        net = n
        return n
    }

    private fun load() {
        val my = ++gen
        val vm = videosMode
        val ang = angle
        val lvl = level
        val date = selDate
        val hour = selHour
        list.removeAllViews()
        status.text = getString(R.string.dvr_connecting)
        progress.visibility = View.VISIBLE
        updateTabs()
        cmd.execute {
            try {
                val n = ensureNet()
                if (n == null) {
                    LogBus.log("[dvr] phone is not on the bike Wi-Fi; interfaces: ${DvrClient.describeInterfaces()}")
                    ui(my) { showNotConnected() }
                    return@execute
                }
                when {
                    !vm -> { val p = DvrClient.photos(n); ui(my) { showPhotos(p) } }
                    lvl == 0 -> { val d = DvrClient.dates(n, ang); ui(my) { showDates(d) } }
                    lvl == 1 && date != null -> { val h = DvrClient.hours(n, date, ang); ui(my) { showHours(date, h) } }
                    date != null && hour != null -> { val c = DvrClient.clips(n, date, hour, ang); ui(my) { showClips(date, hour, c) } }
                    else -> ui(my) { level = 0; load() }
                }
            } catch (e: Exception) {
                net = null
                LogBus.log("[dvr] ${e.javaClass.simpleName}: ${e.message}; interfaces: ${DvrClient.describeInterfaces()}")
                ui(my) { showError(e) }
            }
        }
    }

    // ── screens ─────────────────────────────────────────────────────────────────────────────

    private fun showNotConnected() {
        progress.visibility = View.GONE
        status.text = getString(R.string.dvr_not_on_wifi)
        list.addView(actionButton(R.string.dvr_wifi_settings) {
            try { startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) } catch (_: Exception) {}
        })
        list.addView(actionButton(R.string.dvr_retry) { load() })
    }

    private fun showError(e: Exception) {
        progress.visibility = View.GONE
        status.text = getString(R.string.dvr_error, e.message ?: e.javaClass.simpleName)
        list.addView(actionButton(R.string.dvr_retry) { load() })
    }

    private fun showDates(dates: List<String>) {
        progress.visibility = View.GONE
        status.text = getString(R.string.dvr_pick_day)
        if (dates.isEmpty()) return showEmpty()
        for (d in dates) list.addView(row(null, d, null, { selDate = d; level = 1; load() }))
    }

    private fun showHours(date: String, hours: List<DvrClient.Hour>) {
        progress.visibility = View.GONE
        status.text = getString(R.string.dvr_pick_hour, date)
        if (hours.isEmpty()) return showEmpty()
        for (h in hours) list.addView(row(h.thumbUrl, h.label, null, { selHour = h.time; level = 2; load() }))
    }

    private fun showClips(date: String, hour: String, clips: List<DvrClient.Clip>) {
        progress.visibility = View.GONE
        status.text = "$date · ${DvrClient.Hour(hour, "").label}"
        if (clips.isEmpty()) return showEmpty()
        for (c in clips) {
            val mb = String.format(Locale.getDefault(), "%.1f MB", c.sizeBytes / 1048576.0)
            list.addView(
                row(c.thumbUrl, c.title.ifEmpty { c.name }, mb, { play(c) },
                    getString(R.string.dvr_play) to { _: MaterialButton -> play(c) },
                    getString(R.string.dvr_download) to { b: MaterialButton -> startDownload(b, c.playUrl, c.name, true) },
                ),
            )
        }
    }

    private fun showPhotos(photos: List<DvrClient.Photo>) {
        progress.visibility = View.GONE
        status.text = getString(R.string.dvr_tab_photos)
        if (photos.isEmpty()) return showEmpty()
        for (p in photos) {
            list.addView(
                row(null, photoTitle(p), null, { viewPhoto(p) },
                    getString(R.string.dvr_download) to { b: MaterialButton -> startDownload(b, p.url, p.name, false) },
                ),
            )
        }
    }

    private fun showEmpty() {
        list.addView(TextView(this).apply {
            setText(R.string.dvr_empty)
            textSize = 15f
            setTextColor(color(R.color.text_secondary))
            setPadding(0, dp(16), 0, 0)
        })
    }

    private fun photoTitle(p: DvrClient.Photo): String {
        val m = Regex("(\\d{4}-\\d{2}-\\d{2})-(\\d{2})-(\\d{2})-(\\d{2})").find(p.name)
        val side = getString(if (p.angle == "rear") R.string.dvr_rear else R.string.dvr_front)
        return if (m != null) "${m.groupValues[1]} ${m.groupValues[2]}:${m.groupValues[3]}:${m.groupValues[4]} · $side" else "${p.name} · $side"
    }

    // ── list rows ───────────────────────────────────────────────────────────────────────────

    private fun actionButton(label: Int, onClick: () -> Unit) =
        MaterialButton(this).apply {
            setText(label)
            isAllCaps = false
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
        }

    private fun row(
        thumbUrl: String?,
        title: String,
        sub: String?,
        onClick: (() -> Unit)?,
        vararg actions: Pair<String, (MaterialButton) -> Unit>,
    ): View {
        val card = MaterialCardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
            radius = dp(14).toFloat()
            setCardBackgroundColor(color(R.color.surface))
            setContentPadding(dp(10), dp(10), dp(10), dp(10))
            if (onClick != null) setOnClickListener { onClick() }
        }
        val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        if (thumbUrl != null) {
            val iv = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(color(R.color.bg))
            }
            line.addView(iv, LinearLayout.LayoutParams(dp(104), dp(58)).apply { marginEnd = dp(12) })
            loadThumb(iv, thumbUrl)
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(TextView(this).apply {
            text = title
            textSize = 16f
            setTextColor(color(R.color.text_primary))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        })
        if (sub != null) texts.addView(TextView(this).apply {
            text = sub
            textSize = 13f
            setTextColor(color(R.color.text_secondary))
        })
        line.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (actions.isNotEmpty()) {
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            for ((label, act) in actions) {
                val b = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                    text = label
                    isAllCaps = false
                    textSize = 12f
                }
                b.setOnClickListener { act(b) }
                col.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
            line.addView(col)
        }
        card.addView(line)
        return card
    }

    private fun loadThumb(iv: ImageView, url: String) {
        iv.tag = url
        thumbs.get(url)?.let { iv.setImageBitmap(it); return }
        val n = net
        try {
            thumbIo.execute {
                try {
                    val bmp = decode(DvrClient.fetchBytes(n, url), 320) ?: return@execute
                    thumbs.put(url, bmp)
                    runOnUiThread { if (iv.tag == url) iv.setImageBitmap(bmp) }
                } catch (_: Exception) {
                }
            }
        } catch (_: Exception) {
            // pool already shut down (screen closed)
        }
    }

    private fun decode(bytes: ByteArray, maxWidth: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxWidth) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    // ── play / view / download ──────────────────────────────────────────────────────────────

    /** MediaPlayer opens its own connection inside this process, so the process is pointed at the bike's Wi-Fi while a clip plays. */
    private fun play(c: DvrClient.Clip) {
        val n = net
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val before = cm?.boundNetworkForProcess
        var bound = false
        if (n != null && cm != null) bound = try { cm.bindProcessToNetwork(n) } catch (_: Exception) { false }

        val dlg = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val frame = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val vv = VideoView(this)
        frame.addView(vv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER))
        val spinner = ProgressBar(this)
        frame.addView(spinner, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        val mc = MediaController(this)
        mc.setAnchorView(vv)
        vv.setMediaController(mc)
        vv.setOnPreparedListener { spinner.visibility = View.GONE; vv.start() }
        vv.setOnErrorListener { _, _, _ ->
            spinner.visibility = View.GONE
            Toast.makeText(this, R.string.dvr_play_failed, Toast.LENGTH_LONG).show()
            true
        }
        dlg.setContentView(frame)
        dlg.setOnDismissListener {
            try { vv.stopPlayback() } catch (_: Exception) {}
            if (bound) try { cm?.bindProcessToNetwork(before) } catch (_: Exception) {}
        }
        dlg.show()
        vv.setVideoURI(Uri.parse(DvrClient.httpUrl(c.playUrl)))
    }

    private fun viewPhoto(p: DvrClient.Photo) {
        val n = net
        val dlg = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val frame = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val iv = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        frame.addView(iv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val spinner = ProgressBar(this)
        frame.addView(spinner, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        frame.setOnClickListener { dlg.dismiss() }
        dlg.setContentView(frame)
        dlg.show()
        dl.execute {
            try {
                val bmp = decode(DvrClient.fetchBytes(n, p.url), 2048)
                runOnUiThread { if (dlg.isShowing) { spinner.visibility = View.GONE; iv.setImageBitmap(bmp) } }
            } catch (e: Exception) {
                LogBus.log("[dvr] photo failed: ${e.message}")
                runOnUiThread { spinner.visibility = View.GONE; Toast.makeText(this, R.string.dvr_play_failed, Toast.LENGTH_LONG).show() }
            }
        }
    }

    private fun startDownload(btn: MaterialButton, rawUrl: String, fileName: String, isVideo: Boolean) {
        val n = net
        val app = applicationContext
        btn.isEnabled = false
        btn.text = "0%"
        dl.execute {
            try {
                DvrClient.download(app, n, rawUrl, fileName, isVideo) { p -> runOnUiThread { btn.text = "$p%" } }
                val folder = (if (isVideo) "Movies" else "Pictures") + "/ZontesDVR"
                runOnUiThread {
                    btn.text = "✓"
                    Toast.makeText(app, app.getString(R.string.dvr_saved, folder), Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                LogBus.log("[dvr] download failed: ${e.javaClass.simpleName}: ${e.message}")
                runOnUiThread {
                    btn.isEnabled = true
                    btn.text = app.getString(R.string.dvr_download)
                    Toast.makeText(app, app.getString(R.string.dvr_download_failed, e.message ?: ""), Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
