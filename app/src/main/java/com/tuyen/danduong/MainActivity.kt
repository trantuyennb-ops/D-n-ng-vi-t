package com.tuyen.danduong

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.view.WindowManager
import android.webkit.GeolocationPermissions
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.webkit.WebViewAssetLoader
import java.io.ByteArrayInputStream
import java.net.URL
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory

class MainActivity : Activity(), TextToSpeech.OnInitListener {

    private lateinit var web: WebView
    private lateinit var assetLoader: WebViewAssetLoader
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var locationManager: LocationManager? = null
    private var wantLocation = false
    private var lastGpsTime = 0L
    private val main = Handler(Looper.getMainLooper())
    private var userAgent = ""

    // Android cũ (dưới 7.1) thiếu chứng chỉ gốc Let's Encrypt mà máy chủ bản đồ/tìm đường dùng.
    // Trên các máy đó, app tự tải dữ liệu bằng chứng chỉ đóng kèm thay cho WebView.
    private val needOwnTls = Build.VERSION.SDK_INT < 25
    private val sslFactory: SSLSocketFactory? by lazy {
        try {
            val ks = KeyStore.getInstance(KeyStore.getDefaultType()); ks.load(null, null)
            val sys = KeyStore.getInstance("AndroidCAStore"); sys.load(null, null)
            for (a in sys.aliases()) ks.setCertificateEntry(a, sys.getCertificate(a))
            val cf = CertificateFactory.getInstance("X.509")
            listOf(R.raw.isrg_root_x1, R.raw.isrg_root_x2).forEachIndexed { i, id ->
                resources.openRawResource(id).use { ks.setCertificateEntry("dd_extra_$i", cf.generateCertificate(it)) }
            }
            val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()); tmf.init(ks)
            val ctx = SSLContext.getInstance("TLS"); ctx.init(null, tmf.trustManagers, null)
            ctx.socketFactory
        } catch (e: Exception) { null }
    }
    private val skipHeaders = setOf("accept-encoding", "if-none-match", "if-modified-since", "range", "origin")

    private fun nativeFetch(req: WebResourceRequest): WebResourceResponse? {
        val f = sslFactory ?: return null
        return try {
            val c = URL(req.url.toString()).openConnection() as HttpsURLConnection
            c.sslSocketFactory = f
            c.connectTimeout = 15000; c.readTimeout = 25000
            c.instanceFollowRedirects = true
            req.requestHeaders?.forEach { (k, v) -> if (k.lowercase() !in skipHeaders) c.setRequestProperty(k, v) }
            c.setRequestProperty("User-Agent", userAgent)
            val code = c.responseCode
            if (code in 300..399) return null
            val stream = (if (code >= 400) c.errorStream else c.inputStream) ?: ByteArrayInputStream(ByteArray(0))
            val ct = c.contentType ?: "application/octet-stream"
            val mime = ct.substringBefore(';').trim()
            val enc = Regex("charset=([^;]+)", RegexOption.IGNORE_CASE).find(ct)?.groupValues?.get(1)?.trim()
            val headers = HashMap<String, String>()
            for ((k, v) in c.headerFields) {
                if (k == null || v.isNullOrEmpty()) continue
                val lk = k.lowercase()
                if (lk == "content-encoding" || lk == "content-length" || lk == "transfer-encoding" || lk.startsWith("access-control")) continue
                headers[k] = v.joinToString(",")
            }
            headers["Access-Control-Allow-Origin"] = "*"
            val reason = c.responseMessage?.takeIf { it.isNotBlank() } ?: "OK"
            WebResourceResponse(mime, enc, code, reason, headers, stream)
        } catch (e: Exception) { null }
    }

    private val listener = object : LocationListener {
        override fun onLocationChanged(loc: Location) {
            // Ưu tiên GPS; chỉ dùng vị trí mạng khi GPS im lặng quá 10 giây
            if (loc.provider == LocationManager.GPS_PROVIDER) lastGpsTime = System.currentTimeMillis()
            else if (System.currentTimeMillis() - lastGpsTime < 10_000) return
            val spd = if (loc.hasSpeed()) loc.speed.toDouble() else -1.0
            val brg = if (loc.hasBearing()) loc.bearing.toDouble() else -1.0
            val acc = if (loc.hasAccuracy()) loc.accuracy.toDouble() else 30.0
            val js = "window.NativeLoc&&NativeLoc(${loc.latitude},${loc.longitude},$acc,$spd,$brg)"
            web.evaluateJavascript(js, null)
        }
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
        override fun onProviderEnabled(p: String) {}
        override fun onProviderDisabled(p: String) {
            if (p == LocationManager.GPS_PROVIDER) toast("Hãy bật GPS (Vị trí) để dẫn đường")
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tts = TextToSpeech(this, this)
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager

        assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        web = WebView(this)
        setContentView(web)
        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            setGeolocationEnabled(true)
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            mediaPlaybackRequiresUserGesture = false
            userAgentString = "$userAgentString DanDuongViet/1.0 (Android navigation app)"
        }
        userAgent = web.settings.userAgentString
        web.addJavascriptInterface(Bridge(), "Android")
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, req: WebResourceRequest): WebResourceResponse? {
                val u = req.url
                if (u.host == "appassets.androidplatform.net") return assetLoader.shouldInterceptRequest(u)
                if (needOwnTls && u.scheme == "https" && req.method == "GET") return nativeFetch(req)
                return null
            }

            override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                val u = req.url
                if (u.host == "appassets.androidplatform.net") return false
                // Liên kết bên ngoài (ví dụ ghi công bản đồ) mở bằng trình duyệt
                try { startActivity(Intent(Intent.ACTION_VIEW, u)) } catch (_: Exception) {}
                return true
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onGeolocationPermissionsShowPrompt(origin: String, cb: GeolocationPermissions.Callback) {
                cb.invoke(origin, true, false)
            }
        }
        web.loadUrl("https://appassets.androidplatform.net/assets/index.html")
    }

    /* ---------- Cầu nối JavaScript <-> Android ---------- */
    inner class Bridge {
        @JavascriptInterface fun startLocation() { main.post { requestLocation() } }
        @JavascriptInterface fun diag() {
            Thread {
                val j = try { runDiag() } catch (e: Exception) { JSONObject().put("error", e.toString()).toString() }
                main.post { web.evaluateJavascript("window.onDiag&&onDiag(" + JSONObject.quote(j) + ")", null) }
            }.start()
        }
        @JavascriptInterface fun speak(text: String) { main.post { say(text) } }
        @JavascriptInterface fun speakAdd(text: String) { main.post { if (ttsReady) tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "nav2") } }
        @JavascriptInterface fun stopSpeak() { main.post { tts?.stop() } }
        @JavascriptInterface fun keepScreen(on: Boolean) {
            main.post {
                if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }

    /* ---------- Kiểm tra lỗi ---------- */
    private fun webViewInfo(): String {
        if (Build.VERSION.SDK_INT >= 26) {
            try { WebView.getCurrentWebViewPackage()?.let { return it.packageName + " " + it.versionName } } catch (_: Exception) {}
        }
        for (p in listOf("com.google.android.webview", "com.android.webview", "com.android.chrome")) {
            try { val i = packageManager.getPackageInfo(p, 0); return p + " " + i.versionName } catch (_: Exception) {}
        }
        return "không rõ"
    }

    private fun testUrl(url: String, own: Boolean): String = try {
        val c = URL(url).openConnection() as HttpsURLConnection
        if (own) sslFactory?.let { c.sslSocketFactory = it }
        c.connectTimeout = 10000; c.readTimeout = 10000
        c.setRequestProperty("User-Agent", userAgent)
        val code = c.responseCode
        c.disconnect()
        "mã $code"
    } catch (e: Exception) { (e.javaClass.simpleName + ": " + (e.message ?: "")).take(140) }

    private fun runDiag(): String {
        val o = JSONObject()
        o.put("release", Build.VERSION.RELEASE); o.put("sdk", Build.VERSION.SDK_INT)
        o.put("device", Build.MANUFACTURER + " " + Build.MODEL + " · " + Build.HARDWARE)
        o.put("webview", webViewInfo())
        o.put("locPerm", hasLocPerm())
        o.put("gpsOn", try { locationManager?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true } catch (_: Exception) { false })
        o.put("ttsReady", ttsReady)
        val t = tts
        o.put("ttsDefault", try { t?.defaultEngine ?: "" } catch (_: Exception) { "" })
        val eng = JSONArray(); try { t?.engines?.forEach { eng.put(it.label.toString() + " (" + it.name + ")") } } catch (_: Exception) {}
        o.put("ttsEngines", eng)
        val vi = try { t?.isLanguageAvailable(Locale("vi", "VN")) } catch (_: Exception) { null }
        o.put("viStatus", when (vi) {
            null -> "không kiểm tra được"
            TextToSpeech.LANG_MISSING_DATA -> "❌ thiếu dữ liệu (cần tải giọng Tiếng Việt)"
            TextToSpeech.LANG_NOT_SUPPORTED -> "❌ công cụ hiện tại không hỗ trợ tiếng Việt"
            else -> "✅ có ($vi)"
        })
        o.put("ssl", sslFactory != null)
        val net = JSONArray()
        val urls = listOf(
            "Tìm địa điểm" to "https://nominatim.openstreetmap.org/status",
            "Tìm đường" to "https://routing.openstreetmap.de/routed-car/route/v1/driving/105.97,20.25;105.90,20.25?overview=false",
            "Bản đồ" to "https://tile.openstreetmap.org/10/814/450.png")
        for ((name, u) in urls) {
            val plain = testUrl(u, false); val own = testUrl(u, true)
            net.put(JSONObject().put("name", name).put("ok", own.startsWith("mã 2")).put("res", "thường: $plain | chứng chỉ kèm: $own"))
        }
        o.put("net", net)
        return o.toString()
    }

    /* ---------- Vị trí ---------- */
    private fun hasLocPerm() =
        Build.VERSION.SDK_INT < 23 ||
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun requestLocation() {
        wantLocation = true
        if (!hasLocPerm() && Build.VERSION.SDK_INT >= 23) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 1)
            return
        }
        startUpdates()
    }

    @SuppressLint("MissingPermission")
    private fun startUpdates() {
        val lm = locationManager ?: return
        if (!hasLocPerm()) return
        lm.removeUpdates(listener)
        var any = false
        if (lm.allProviders.contains(LocationManager.GPS_PROVIDER)) {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, listener); any = true
            if (!lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) toast("Hãy bật GPS (Vị trí) để dẫn đường")
        }
        if (lm.allProviders.contains(LocationManager.NETWORK_PROVIDER)) {
            lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 2000L, 0f, listener); any = true
        }
        // Gửi ngay vị trí gần nhất đã biết để bản đồ hiện nhanh
        val last = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { lm.allProviders.contains(it) }
            .mapNotNull { lm.getLastKnownLocation(it) }
            .maxByOrNull { it.time }
        if (last != null) listener.onLocationChanged(last)
        if (!any) toast("Máy không có GPS")
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        if (hasLocPerm()) startUpdates() else toast("Cần cho phép truy cập vị trí để dẫn đường")
    }

    /* ---------- Giọng nói tiếng Việt ---------- */
    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) { toast("Máy chưa có bộ đọc giọng nói"); return }
        val r = tts?.setLanguage(Locale("vi", "VN")) ?: TextToSpeech.LANG_NOT_SUPPORTED
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
            toast("Chưa có giọng tiếng Việt. Hãy cài 'Dịch vụ lời nói của Google' và tải giọng Tiếng Việt.")
            try { startActivity(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)) } catch (_: Exception) {}
        } else ttsReady = true
        tts?.setSpeechRate(1.0f)
    }

    private fun say(text: String) {
        if (!ttsReady) return
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "nav")
    }

    /* ---------- Vòng đời ---------- */
    override fun onResume() {
        super.onResume()
        web.onResume()
        if (wantLocation) startUpdates()
    }

    override fun onPause() {
        // Đang dẫn đường (màn hình giữ sáng) thì vẫn nhận GPS; nếu không thì tạm dừng để tiết kiệm pin
        val navigating = (window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0
        if (!navigating) locationManager?.removeUpdates(listener)
        super.onPause()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        web.evaluateJavascript("(window.onBack&&window.onBack())?'1':'0'") { r ->
            if (r == null || !r.contains("1")) finish()
        }
    }

    override fun onDestroy() {
        locationManager?.removeUpdates(listener)
        tts?.shutdown()
        web.destroy()
        super.onDestroy()
    }

    private fun toast(s: String) = main.post { Toast.makeText(this, s, Toast.LENGTH_LONG).show() }
}
