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
import java.util.Locale

class MainActivity : Activity(), TextToSpeech.OnInitListener {

    private lateinit var web: WebView
    private lateinit var assetLoader: WebViewAssetLoader
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var locationManager: LocationManager? = null
    private var wantLocation = false
    private var lastGpsTime = 0L
    private val main = Handler(Looper.getMainLooper())

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
        web.addJavascriptInterface(Bridge(), "Android")
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, req: WebResourceRequest): WebResourceResponse? =
                assetLoader.shouldInterceptRequest(req.url)

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
