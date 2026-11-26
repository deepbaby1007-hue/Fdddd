package com.sunsang24.autobooking

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.sunsang24.autobooking.databinding.ActivityBookingBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BookingWebViewActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_MODE = "mode"
        const val MODE_LOGIN = "login"
        const val MODE_RUN = "run"

        private const val MOBILE_UA =
            "Mozilla/5.0 (Linux; Android 14; SM-S911N) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/131.0.0.0 Mobile Safari/537.36"

        fun createIntent(context: Context, mode: String): Intent =
            Intent(context, BookingWebViewActivity::class.java).putExtra(EXTRA_MODE, mode)
    }

    private lateinit var binding: ActivityBookingBinding
    private lateinit var settings: BookingSettings
    private var mode: String = MODE_LOGIN
    private var engine: BookingEngine? = null
    private val logs = StringBuilder()

    private val engineListener = object : BookingEngine.Listener {
        override fun onLog(message: String) {
            appendLog(message)
        }

        override fun onStatus(status: String) {
            binding.tvStatus.text = status
            if (status == "완료" || status == "중단") {
                binding.progressBar.visibility = View.GONE
            }
        }

        override fun onFinished(success: Boolean, message: String, keepWebViewOpen: Boolean) {
            binding.progressBar.visibility = View.GONE
            binding.tvStatus.text = if (success) "완료" else "중단"
            appendLog((if (success) "✅ " else "⏹ ") + message)
            BookingSettings.saveLastRun(this@BookingWebViewActivity, binding.tvStatus.text.toString(), logs.toString())
            if (!keepWebViewOpen) {
                binding.btnStop.text = "닫기"
            }
            Toast.makeText(this@BookingWebViewActivity, message, Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBookingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        settings = BookingSettings.load(this)
        mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_LOGIN

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(binding.webView, true)

        setupWebView()

        binding.btnStop.setOnClickListener {
            if (engine != null && mode == MODE_RUN) {
                stopAndFinish("사용자가 중지했습니다.")
            } else {
                CookieManager.getInstance().flush()
                finish()
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (engine != null && mode == MODE_RUN) {
                    stopAndFinish("사용자가 중지했습니다.")
                } else {
                    CookieManager.getInstance().flush()
                    finish()
                }
            }
        })

        if (mode == MODE_RUN) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            binding.tvTitle.text = "자동 예약 실행 중"
            binding.tvStatus.text = "시작 준비"
            binding.progressBar.visibility = View.VISIBLE
            appendLog("자동 예약을 시작합니다.")
            if (settings.date.isBlank()) {
                appendLog("출조 날짜가 비어 있습니다. 설정 화면에서 날짜를 먼저 입력해 주세요.")
                binding.tvStatus.text = "중단"
                binding.progressBar.visibility = View.GONE
                return
            }
            val bookingEngine = BookingEngine(binding.webView, settings, engineListener)
            engine = bookingEngine
            EngineBus.stopper = { reason ->
                stopAndFinish(reason)
                true
            }
            bookingEngine.start()
        } else {
            binding.tvTitle.text = "선상24 로그인"
            binding.tvStatus.text = "카카오 로그인을 진행해 주세요"
            binding.btnStop.text = "닫기"
            appendLog("로그인 상태는 앱에 저장되어, 다음부터는 다시 로그인하지 않아도 됩니다.")
            val date = if (settings.date.isNotBlank()) settings.date else Sunsang24.todayIso()
            val gotoUrl = Sunsang24.scheduleUrl(settings.subdomain, date)
            binding.webView.loadUrl(Sunsang24.loginUrl(gotoUrl))
        }
    }

    private fun setupWebView() {
        val webView = binding.webView

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            javaScriptCanOpenWindowsAutomatically = true
            userAgentString = MOBILE_UA
        }
        webView.setBackgroundColor(Color.WHITE)

        if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            WebView.setWebContentsDebuggingEnabled(true)
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                binding.progressBar.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                binding.progressBar.visibility = View.GONE
                CookieManager.getInstance().flush()
                if (url != null) {
                    engine?.onPageFinished(url)
                }
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val uri = request?.url ?: return false
                val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return false
                if (scheme == "http" || scheme == "https") return false
                if (scheme == "intent") {
                    return try {
                        val intent = Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME)
                        try {
                            startActivity(intent)
                        } catch (e: Exception) {
                            val fallbackUrl = intent.getStringExtra("browser_fallback_url")
                            if (!fallbackUrl.isNullOrBlank()) {
                                view?.loadUrl(fallbackUrl)
                            }
                        }
                        true
                    } catch (e: Exception) {
                        true
                    }
                }
                return try {
                    startActivity(Intent(Intent.ACTION_VIEW, uri))
                    true
                } catch (e: Exception) {
                    true
                }
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                if (request?.isForMainFrame == true) {
                    val description = error?.description?.toString().orEmpty()
                    appendLog("네트워크 오류: " + description)
                    engine?.onPageError(description.ifBlank { "알 수 없는 오류" })
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onJsAlert(
                view: WebView?,
                url: String?,
                message: String?,
                result: JsResult?
            ): Boolean {
                if (message == null || result == null) return false
                val auto = engine?.answerAlert(message)
                if (auto == true) {
                    Toast.makeText(this@BookingWebViewActivity, message, Toast.LENGTH_LONG).show()
                    result.confirm()
                    return true
                }
                AlertDialog.Builder(this@BookingWebViewActivity)
                    .setMessage(message)
                    .setPositiveButton("확인") { _, _ -> result.confirm() }
                    .setOnCancelListener { result.cancel() }
                    .show()
                return true
            }

            override fun onJsConfirm(
                view: WebView?,
                url: String?,
                message: String?,
                result: JsResult?
            ): Boolean {
                if (message == null || result == null) return false
                val auto = engine?.answerConfirm(message)
                if (auto != null) {
                    if (auto) result.confirm() else result.cancel()
                    return true
                }
                AlertDialog.Builder(this@BookingWebViewActivity)
                    .setMessage(message)
                    .setPositiveButton("예") { _, _ -> result.confirm() }
                    .setNegativeButton("아니오") { _, _ -> result.cancel() }
                    .setOnCancelListener { result.cancel() }
                    .show()
                return true
            }

            override fun onJsPrompt(
                view: WebView?,
                url: String?,
                message: String?,
                defaultValue: String?,
                result: JsPromptResult?
            ): Boolean {
                if (message == null || result == null) return false
                AlertDialog.Builder(this@BookingWebViewActivity)
                    .setMessage(message)
                    .setPositiveButton("확인") { _, _ -> result.cancel() }
                    .setOnCancelListener { result.cancel() }
                    .show()
                return true
            }
        }
    }

    private fun appendLog(line: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(Date())
        if (logs.isNotEmpty()) logs.append('\n')
        logs.append(time).append(' ').append(line)
        if (logs.length > 8000) {
            logs.delete(0, logs.length - 8000)
        }
        binding.tvLog.text = logs.toString()
        binding.scrollLog.post { binding.scrollLog.fullScroll(View.FOCUS_DOWN) }
    }

    private fun stopAndFinish(reason: String) {
        engine?.stop(reason)
        engine = null
        CookieManager.getInstance().flush()
        finish()
    }

    override fun onPause() {
        super.onPause()
        CookieManager.getInstance().flush()
        BookingSettings.saveLastRun(this, binding.tvStatus.text.toString(), logs.toString())
    }

    override fun onDestroy() {
        engine?.stop("화면이 닫혀서 중단했습니다.")
        engine = null
        EngineBus.stopper = null
        CookieManager.getInstance().flush()
        binding.webView.apply {
            stopLoading()
            loadUrl("about:blank")
            clearHistory()
            destroy()
        }
        super.onDestroy()
    }
}
