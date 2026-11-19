// SPDX-License-Identifier: AGPL-3.0-or-later
// Part of RideScreen AA (fork of OpenCfMoto). Free software under the GNU AGPL v3 or later.
package dev.zanderp.opencfmoto

import android.content.ContentValues
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.URL
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * Client for the Zontes front/rear camera (DVR) HTTP service, written from what the rider's own
 * bike answered to the official-style app (packet capture, 18 Nov 2026):
 *  - plain HTTP, no login, at 192.168.3.1:7686 (same address range as the dash's own Wi-Fi);
 *  - lists are JSON POSTed to "/" with {"METHOD": "...", ...} and answer {"code":0, ...};
 *  - the clips and pictures are ordinary files fetched with GET (byte ranges are supported).
 * Only read-only commands are used here; nothing is ever deleted or formatted on the camera.
 */
object DvrClient {
    const val HOST = "192.168.3.1"
    const val PORT = 7686
    private const val BASE = "http://$HOST:$PORT"
    private const val UA = "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36"

    class DvrException(message: String) : Exception(message)

    data class Hour(val time: String, val thumbUrl: String) {
        /** "6-7" -> "06:00 - 07:00". */
        val label: String
            get() {
                val p = time.split("-")
                val a = p.getOrNull(0)?.trim()?.toIntOrNull()
                val b = p.getOrNull(1)?.trim()?.toIntOrNull()
                return if (a != null && b != null) String.format("%02d:00 - %02d:00", a, b) else time
            }
    }

    data class Clip(
        val id: Long,
        val name: String,
        val title: String,
        val sizeBytes: Long,
        val angle: String,
        val playUrl: String,
        val thumbUrl: String,
    )

    data class Photo(val name: String, val angle: String, val url: String)

    /** The camera sends addresses without "http://". */
    fun httpUrl(raw: String): String = if (raw.startsWith("http://") || raw.startsWith("https://")) raw else "http://$raw"

    /**
     * The Wi-Fi network the camera lives on: a Wi-Fi network of this phone with an address in
     * 192.168.3.x (joined by this app or by hand in the phone's Wi-Fi settings), else the one the
     * app joined to the bike. Null when the phone isn't on the bike's Wi-Fi.
     */
    fun findNetwork(ctx: Context): Network? {
        val cm = ctx.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
        @Suppress("DEPRECATION")
        val all = try { cm.allNetworks } catch (_: Exception) { emptyArray<Network>() }
        for (n in all) {
            val caps = cm.getNetworkCapabilities(n) ?: continue
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue
            val lp = cm.getLinkProperties(n) ?: continue
            if (lp.linkAddresses.any { it.address.hostAddress?.startsWith("192.168.3.") == true }) return n
        }
        return BikeWifi.currentNetwork
    }

    /** One line per IPv4 address of this phone, for the log when the camera can't be reached. */
    fun describeInterfaces(): String = try {
        val sb = StringBuilder()
        for (ni in NetworkInterface.getNetworkInterfaces()) {
            val addrs = ni.inetAddresses.toList().filter { it.address.size == 4 && !it.isLoopbackAddress }
            if (addrs.isNotEmpty()) sb.append(ni.name).append('=').append(addrs.joinToString("/") { it.hostAddress ?: "?" }).append(' ')
        }
        sb.toString().trim()
    } catch (_: Exception) { "?" }

    private fun open(net: Network?, url: URL): HttpURLConnection =
        (net?.openConnection(url) ?: url.openConnection()) as HttpURLConnection

    /** POST one command and return the whole answer; throws [DvrException] unless code is 0. */
    fun call(net: Network?, body: JSONObject): JSONObject {
        val conn = open(net, URL("$BASE/"))
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 5000
            conn.readTimeout = 12000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("User-Agent", UA)
            val bytes = body.toString().toByteArray(Charsets.UTF_8)
            conn.setFixedLengthStreamingMode(bytes.size)
            conn.outputStream.use { it.write(bytes) }
            val code = conn.responseCode
            if (code != 200) throw DvrException("HTTP $code")
            val text = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val json = JSONObject(text)
            val c = json.optInt("code", -1)
            if (c != 0) throw DvrException("code $c")
            return json
        } finally {
            conn.disconnect()
        }
    }

    private fun JSONArray?.objects(): List<JSONObject> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { optJSONObject(it) }
    }

    /** Days that have recordings, newest first as the camera sends them. [angle] = "front" / "rear". */
    fun dates(net: Network?, angle: String): List<String> {
        val j = call(net, JSONObject().put("METHOD", "VIDEO.DATE").put("previewType", angle))
        val arr = j.optJSONArray("dateList") ?: return emptyList()
        return (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotEmpty() }
    }

    fun hours(net: Network?, date: String, angle: String): List<Hour> {
        val j = call(net, JSONObject().put("METHOD", "VIDEO.TIME").put("date", date).put("previewType", angle))
        return j.optJSONArray("timeBeanList").objects().map { Hour(it.optString("time"), it.optString("thumbUrl")) }
    }

    fun clips(net: Network?, date: String, time: String, angle: String): List<Clip> {
        val j = call(
            net,
            JSONObject().put("METHOD", "VIDEO.INFO.LIST").put("date", date).put("time", time)
                .put("index", 0).put("num", 120).put("sort", "des").put("previewType", angle),
        )
        return j.optJSONArray("videoBeanList").objects().map {
            Clip(
                id = it.optLong("id"),
                name = it.optString("name"),
                title = it.optString("title"),
                sizeBytes = it.optLong("size"),
                angle = it.optString("angle"),
                playUrl = it.optString("playUrl"),
                thumbUrl = it.optString("thumbUrl"),
            )
        }.filter { it.playUrl.isNotEmpty() }
    }

    fun photos(net: Network?): List<Photo> {
        val j = call(
            net,
            JSONObject().put("METHOD", "PICTURE.INFO.LIST").put("index", 0).put("num", 100)
                .put("previewType", "all").put("sort", "des"),
        )
        return j.optJSONArray("pictureBeanList").objects().map {
            Photo(it.optString("name"), it.optString("angle"), it.optString("pictureUrl"))
        }.filter { it.url.isNotEmpty() }
    }

    /** Ports a camera might use for a live picture (RTSP / HTTP / MJPEG / RTMP and common extras). */
    private val PROBE_PORTS = listOf(80, 443, 554, 7070, 7687, 8000, 8080, 8081, 8554, 8888, 1935, 5000, 9000)

    /**
     * Read-only look for a live-video door on the camera: tries to open each port in [PROBE_PORTS]
     * on [HOST] (and 7686), and for an open one sends only an RTSP "OPTIONS" or an HTTP "GET /".
     * Nothing is changed on the camera. Returns one line per port that answered, or a note that
     * none did.
     */
    fun probeLive(net: Network?): List<String> {
        val ports = (PROBE_PORTS + PORT).distinct()
        val pool = Executors.newFixedThreadPool(8)
        try {
            val jobs = ports.map { port ->
                pool.submit(Callable<String?> {
                    val sock = (net?.socketFactory?.createSocket() ?: java.net.Socket())
                    try {
                        sock.connect(InetSocketAddress(HOST, port), 1500)
                        sock.soTimeout = 2000
                        val rtsp = port == 554 || port == 8554
                        val req = if (rtsp) "OPTIONS rtsp://$HOST:$port/ RTSP/1.0\r\nCSeq: 1\r\n\r\n"
                        else "GET / HTTP/1.0\r\nHost: $HOST\r\nUser-Agent: $UA\r\n\r\n"
                        sock.getOutputStream().apply { write(req.toByteArray()); flush() }
                        val buf = ByteArray(300)
                        val n = try { sock.getInputStream().read(buf) } catch (_: Exception) { -1 }
                        val first = if (n > 0) String(buf, 0, n).lineSequence().firstOrNull()?.trim().orEmpty() else ""
                        "$port: open ${if (rtsp) "(RTSP) " else ""}${first.take(80)}"
                    } catch (_: Exception) {
                        null
                    } finally {
                        try { sock.close() } catch (_: Exception) {}
                    }
                })
            }
            return jobs.mapNotNull { try { it.get() } catch (_: Exception) { null } }
        } finally {
            pool.shutdown()
        }
    }

    /** Raw bytes of a small file (thumbnail / picture). */
    fun fetchBytes(net: Network?, rawUrl: String): ByteArray {
        val conn = open(net, URL(httpUrl(rawUrl)))
        try {
            conn.connectTimeout = 5000
            conn.readTimeout = 15000
            conn.setRequestProperty("User-Agent", UA)
            if (conn.responseCode != 200) throw DvrException("HTTP ${conn.responseCode}")
            return conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Copy a clip or picture from the camera into the phone's gallery (Movies/ZontesDVR or
     * Pictures/ZontesDVR). [onProgress] gets 0..100 when the size is known.
     */
    fun download(ctx: Context, net: Network?, rawUrl: String, fileName: String, isVideo: Boolean, onProgress: (Int) -> Unit): Uri {
        val resolver = ctx.applicationContext.contentResolver
        val dir = (if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES) + "/ZontesDVR"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, if (isVideo) "video/mp4" else "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, dir)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val collection = if (isVideo) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        else MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values) ?: throw DvrException("MediaStore")
        try {
            val conn = open(net, URL(httpUrl(rawUrl)))
            try {
                conn.connectTimeout = 5000
                conn.readTimeout = 20000
                conn.setRequestProperty("User-Agent", UA)
                if (conn.responseCode != 200) throw DvrException("HTTP ${conn.responseCode}")
                val total = conn.contentLengthLong
                val out = resolver.openOutputStream(uri) ?: throw DvrException("MediaStore")
                conn.inputStream.use { input ->
                    out.use { o ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        var lastPct = -1
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            o.write(buf, 0, n)
                            done += n
                            if (total > 0) {
                                val pct = (done * 100 / total).toInt()
                                if (pct != lastPct) { lastPct = pct; onProgress(pct) }
                            }
                        }
                    }
                }
            } finally {
                conn.disconnect()
            }
            val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)
            return uri
        } catch (e: Exception) {
            try { resolver.delete(uri, null, null) } catch (_: Exception) {}
            throw e
        }
    }
}
