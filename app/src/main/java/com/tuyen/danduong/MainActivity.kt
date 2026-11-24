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
import android.speech.RecognizerIntent
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
import java.security.Security
import org.conscrypt.Conscrypt
import java.security.cert.CertificateFactory
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import java.util.concurrent.TimeUnit
import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.Request

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
    private val trust: Pair<SSLSocketFactory, X509TrustManager>? by lazy {
        try {
            val ks = KeyStore.getInstance(KeyStore.getDefaultType()); ks.load(null, null)
            val sys = KeyStore.getInstance("AndroidCAStore"); sys.load(null, null)
            for (a in sys.aliases()) ks.setCertificateEntry(a, sys.getCertificate(a))
            val cf = CertificateFactory.getInstance("X.509")
            listOf(R.raw.isrg_root_x1, R.raw.isrg_root_x2).forEachIndexed { i, id ->
                resources.openRawResource(id).use { ks.setCertificateEntry("dd_extra_$i", cf.generateCertificate(it)) }
            }
            val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()); tmf.init(ks)
            val tm = tmf.trustManagers.first { it is X509TrustManager } as X509TrustManager
            val ctx = SSLContext.getInstance("TLS"); ctx.init(null, arrayOf(tm), null)
            Pair(ctx.socketFactory, tm)
        } catch (e: Exception) { null }
    }
    private val sslFactory: SSLSocketFactory? get() = trust?.first
    // OkHttp: bộ tải dữ liệu hiện đại, dùng được kết nối bảo mật mới (TLS 1.3) qua Conscrypt
    private val http: OkHttpClient? by lazy {
        val t = trust ?: return@lazy null
        try {
            OkHttpClient.Builder()
                .sslSocketFactory(t.first, t.second)
                .connectTimeout(15, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS)
                .followRedirects(true).followSslRedirects(true)
                .cache(Cache(java.io.File(cacheDir, "http"), 100L * 1024 * 1024)) // lưu ảnh bản đồ đã tải
                .build()
        } catch (e: Throwable) { null }
    }
    private val skipHeaders = setOf("accept-encoding", "if-none-match", "if-modified-since", "range", "origin")

    private fun nativeFetch(req: WebResourceRequest): WebResourceResponse? {
        val client = http ?: return null
        return try {
            val rb = Request.Builder().url(req.url.toString())
            req.requestHeaders?.forEach { (k, v) -> if (k.lowercase() !in skipHeaders && !k.equals("User-Agent", true)) rb.header(k, v) }
            rb.header("User-Agent", userAgent)
            val resp = client.newCall(rb.build()).execute()
            val code = resp.code
            if (code in 300..399) { resp.close(); return null }
            val body = resp.body ?: run { resp.close(); return null }
            val ct = resp.header("Content-Type") ?: "application/octet-stream"
            val mime = ct.substringBefore(';').trim()
            val enc = Regex("charset=([^;]+)", RegexOption.IGNORE_CASE).find(ct)?.groupValues?.get(1)?.trim()
            val headers = HashMap<String, String>()
            for (name in resp.headers.names()) {
                val lk = name.lowercase()
                if (lk == "content-encoding" || lk == "content-length" || lk == "transfer-encoding" || lk.startsWith("access-control")) continue
                headers[name] = resp.headers.values(name).joinToString(",")
            }
            headers["Access-Control-Allow-Origin"] = "*"
            val reason = resp.message.takeIf { it.isNotBlank() } ?: "OK"
            WebResourceResponse(mime, enc, code, reason, headers, body.byteStream())
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
        // Android cũ không hỗ trợ kết nối bảo mật kiểu mới -> dùng Conscrypt đóng kèm
        if (Build.VERSION.SDK_INT < 29) {
            try { Security.insertProviderAt(Conscrypt.newProvider(), 1) } catch (_: Throwable) {}
        }
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
        @JavascriptInterface fun voiceSearch() { main.post { startVoice() } }
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

    /* ---------- Tìm kiếm bằng giọng nói ---------- */
    private fun startVoice() {
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "vi-VN")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "vi-VN")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Nói tên điểm đến...")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }
        try { startActivityForResult(i, 2) }
        catch (e: Exception) { toast("Máy chưa có tính năng nhận giọng nói. Hãy cài ứng dụng \"Google\" từ CH Play.") }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 2) {
            val text = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull() ?: ""
            if (text.isEmpty()) toast("Không nghe rõ, hãy thử lại")
            web.evaluateJavascript("window.onVoice&&onVoice(" + JSONObject.quote(text) + ")", null)
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
        if (own) {
            val cl = http ?: throw Exception("không tạo được OkHttp")
            cl.newCall(Request.Builder().url(url).header("User-Agent", userAgent).build()).execute().use { "mã ${it.code}" }
        } else testPlain(url)
    } catch (e: Exception) { (e.javaClass.simpleName + ": " + (e.message ?: "")).take(140) }

    private fun testPlain(url: String): String = try {
        val c = URL(url).openConnection() as HttpsURLConnection
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
        o.put("ttsUsed", ttsUsed)
        o.put("tls", try { Security.getProviders()[0].name } catch (_: Exception) { "?" })
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
            net.put(JSONObject().put("name", name).put("ok", own.startsWith("mã 2")).put("res", "thường: $plain | app: $own"))
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
    // Dò lần lượt mọi bộ đọc trong máy, dùng bộ đầu tiên đọc được tiếng Việt
    private var ttsEngines: List<String>? = null
    private var ttsIdx = -1
    private var ttsUsed = ""
    private var viMissingData = false

    override fun onInit(status: Int) {
        val t = tts
        if (status == TextToSpeech.SUCCESS && t != null) {
            val r = try { t.setLanguage(Locale("vi", "VN")) } catch (_: Exception) { TextToSpeech.LANG_NOT_SUPPORTED }
            if (r >= TextToSpeech.LANG_AVAILABLE) {
                ttsReady = true
                ttsUsed = if (ttsIdx >= 0) ttsEngines!![ttsIdx] else ((try { t.defaultEngine } catch (_: Exception) { null }) ?: "mặc định")
                t.setSpeechRate(1.0f)
                return
            }
            if (r == TextToSpeech.LANG_MISSING_DATA) viMissingData = true
        }
        if (ttsEngines == null) ttsEngines = (try { t?.engines?.map { it.name } } catch (_: Exception) { null }) ?: emptyList()
        ttsIdx++
        val list = ttsEngines!!
        if (ttsIdx < list.size) {
            try { t?.shutdown() } catch (_: Exception) {}
            tts = TextToSpeech(this, this, list[ttsIdx])
            return
        }
        if (viMissingData) {
            toast("Chưa tải giọng Tiếng Việt. Hãy tải giọng Tiếng Việt trong cài đặt giọng nói.")
            try { startActivity(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)) } catch (_: Exception) {}
        } else {
            toast("Máy chưa có giọng đọc tiếng Việt. Hãy cập nhật 'Dịch vụ lời nói của Google' trên CH Play hoặc cài eSpeak NG.")
        }
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
