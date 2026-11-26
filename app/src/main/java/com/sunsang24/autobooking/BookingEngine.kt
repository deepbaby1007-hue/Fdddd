package com.sunsang24.autobooking

import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener

/**
 * 자동 예약 실행 엔진.
 *
 * WebView 를 실제로 띄워 놓고(사용자가 화면에서 지켜볼 수 있게) 다음과 같은 순서로 움직입니다.
 *
 *   1) 출항일정 페이지를 연다            (/ship/schedule_fleet/YYYYMM)
 *   2) 자리 확인 스크립트를 주입한다      → SEAT / NO_SEAT / CLOSED ...
 *   3) 자리가 있으면 예약 페이지로 이동   (/mypage/reservation_ready/{no})
 *   4) 인원·예약자명·연락처·약관을 채운다  → READY
 *   5) (자동 최종 확인이 켜져 있으면) 확인 창을 자동 승인하고 예약을 접수한다
 *   6) 예약 완료 페이지가 뜨면 예약번호를 읽고 알린다
 *
 * "결제/최종확인 직전 멈추고 알림" 모드에서는 4)까지만 하고 멈춥니다.
 * 그러면 사용자가 화면에서 '예약하기' 버튼만 누르면 됩니다.
 */
class BookingEngine(
    private val webView: WebView,
    private val settings: BookingSettings,
    private val listener: Listener
) {

    interface Listener {
        fun onLog(message: String)
        fun onStatus(status: String)

        /**
         * @param keepWebViewOpen true 면 화면을 닫지 않고 그대로 둡니다.
         *        (예: 예약 버튼 직전에서 멈춘 경우, 사용자가 직접 버튼을 눌러야 하므로)
         */
        fun onFinished(success: Boolean, message: String, keepWebViewOpen: Boolean)
    }

    enum class Phase { IDLE, WAITING, SCANNING, FOUND, PREPARING, CONFIRMING, DONE }

    private val handler = Handler(Looper.getMainLooper())

    private var phase = Phase.IDLE
    private var running = false
    private var token = 0
    private var scanCount = 0
    private var scheduleNo = ""
    private var readyRetries = 0

    /** 예약 확인 창을 자동 승인해도 되는 상태인지 (액티비티의 onJsConfirm 이 봅니다) */
    val isConfirming: Boolean
        get() = running && phase == Phase.CONFIRMING

    private val pageTimeout = Runnable { onPageTimeout() }

    private val countdown = object : Runnable {
        override fun run() {
            if (!running) return
            val left = Sunsang24.millisUntil(settings.openTime)
            if (left <= 0L) {
                listener.onLog("지정한 시각이 되었습니다. 감시를 시작합니다.")
                beginScan()
            } else {
                if (left < 60_000L) {
                    listener.onStatus("대기 중 · " + (left / 1000) + "초 남음")
                } else {
                    listener.onStatus("대기 중 · " + Sunsang24.prettyTime(settings.openTime) + " 오픈런")
                }
                handler.postDelayed(this, if (left < 60_000L) 1_000L else 15_000L)
            }
        }
    }

    // ──────────────────────────────────────────────────────────────── 실행/중지

    fun start() {
        if (running) return
        running = true
        phase = Phase.IDLE
        scanCount = 0
        scheduleNo = ""

        listener.onLog("설정: " + settings.summary())
        if (settings.shipName.isBlank()) {
            listener.onLog("배 이름을 비워 두었습니다. 그 날짜에 예약 가능한 첫 번째 배를 노립니다.")
        }

        if (settings.mode == BookingSettings.Mode.OPENRUN) {
            val left = Sunsang24.millisUntil(settings.openTime)
            if (left > 0L) {
                phase = Phase.WAITING
                listener.onStatus("대기 중 · " + settings.openTime + " 오픈런")
                listener.onLog("오픈 시각까지 " + (left / 1000) + "초 기다립니다.")
                handler.postDelayed(countdown, 300L)
                return
            }
            listener.onLog("오픈 시각이 이미 지나서 바로 시작합니다.")
        }

        beginScan()
    }

    fun stop(reason: String = "사용자가 중지했습니다.") {
        // 이미 끝난 작업(성공 포함)을 덮어쓰지 않습니다.
        if (!running) return
        stopInternal(false, reason)
    }

    private fun stopInternal(success: Boolean, message: String, keepWebViewOpen: Boolean = false) {
        val wasRunning = running
        running = false
        phase = if (success) Phase.DONE else Phase.IDLE
        handler.removeCallbacksAndMessages(null)
        listener.onStatus(if (success) "완료" else "중단")
        if (wasRunning || success) {
            listener.onLog(message)
        }
        listener.onFinished(success, message, keepWebViewOpen)
    }

    // ─────────────────────────────────────────────────────────── 페이지 이동

    private fun beginScan() {
        if (!running) return
        phase = Phase.SCANNING
        scanCount++
        listener.onStatus("감시 중 · " + scanCount + "회")
        val url = Sunsang24.scheduleUrl(settings.subdomain, settings.date) +
            "?_=" + System.currentTimeMillis()
        load(url)
    }

    private fun load(url: String) {
        token++
        handler.removeCallbacks(pageTimeout)
        handler.postDelayed(pageTimeout, PAGE_TIMEOUT_MS)
        webView.loadUrl(url)
    }

    private fun intervalMs(): Long = maxOf(settings.intervalSec, BookingSettings.MIN_INTERVAL_SEC) * 1000L

    private fun retryLater() {
        if (!running) return
        handler.postDelayed({ beginScan() }, intervalMs())
    }

    private fun onPageTimeout() {
        if (!running) return
        listener.onLog("페이지 응답이 없습니다. 잠시 후 다시 시도합니다.")
        retryLater()
    }

    /** 액티비티의 WebViewClient 에서 호출 */
    fun onPageFinished(url: String) {
        if (!running) return
        handler.removeCallbacks(pageTimeout)
        when {
            url.contains("/mypage/reservation_detail/") -> onBooked()
            url.contains("/mypage/reservation_ready/") -> prepare()
            url.contains("schedule_fleet") -> scan()
            url.contains("user/login") || url.contains("/user/login") ->
                stopInternal(false, "선상24 로그인이 필요합니다. '카카오 로그인 먼저 하기'로 로그인한 뒤 다시 시작해 주세요.")
            else -> retryLater()
        }
    }

    /** 액티비티의 WebViewClient 에서 네트워크 오류가 났을 때 */
    fun onPageError(description: String) {
        if (!running) return
        listener.onLog("페이지 오류: " + description)
    }

    // ─────────────────────────────────────────────────────────────── 스크립트

    private fun evaluate(script: String, onResult: (JSONObject?) -> Unit) {
        webView.evaluateJavascript(script) { raw ->
            onResult(parseJson(raw))
        }
    }

    private fun parseJson(raw: String?): JSONObject? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty() || text == "null" || text == "undefined") return null
        try {
            return JSONObject(text)
        } catch (e: JSONException) {
            // 문자열로 한 번 더 감싸져 오는 경우를 풀어 봅니다.
            return try {
                val inner = JSONTokener(text).nextValue()
                if (inner is String) JSONObject(inner) else null
            } catch (e2: Exception) {
                null
            }
        }
    }

    private fun scan() {
        val script = Sunsang24Automation.scanSchedule(settings.date, settings.shipName, settings.people)
        evaluate(script) { obj ->
            if (!running || phase != Phase.SCANNING) return@evaluate
            val status = obj?.optString("status").orEmpty()
            val loginRequired = obj?.optBoolean("login", false) ?: false

            when (status) {
                "SEAT" -> {
                    scheduleNo = obj?.optString("scheduleNo").orEmpty()
                    val ship = obj?.optString("ship").orEmpty()
                    val remain = obj?.optInt("remain", -1) ?: -1
                    phase = Phase.FOUND
                    listener.onStatus("자리 발견 · " + ship + " " + remain + "석")
                    listener.onLog("자리 발견: " + ship + " / 남은자리 " + remain + "명 / 예약번호(schedule_no) " + scheduleNo)
                    Notifier.alert(
                        webView.context,
                        "자리 발견!",
                        ship + " " + settings.date + " · 남은자리 " + remain + "명",
                        2001
                    )
                    handler.postDelayed({ openReady() }, 500L)
                }
                "NO_SEAT" -> {
                    listener.onLog("자리 없음 · " + (obj?.optString("ship") ?: "") + " 남은자리 " + (obj?.optInt("remain", -1) ?: -1) + "명")
                    retryLater()
                }
                "CLOSED" -> {
                    listener.onLog("예약마감 상태입니다. 계속 지켜봅니다.")
                    retryLater()
                }
                "NO_SHIP" -> {
                    listener.onLog("그 날짜에 '" + settings.shipName + "' 편이 없습니다. 배 이름과 날짜를 확인해 주세요.")
                    retryLater()
                }
                "DEFAULT_FLOW" -> {
                    val ship = obj?.optString("ship").orEmpty()
                    stopInternal(
                        false,
                        "'" + ship + "' 는 기본 스케줄을 골라야 하는 다른 예약 방식을 쓰고 있어 앱이 지원하지 않습니다. " +
                            "그 배는 선상24에서 직접 예약해 주시거나, 다른 배를 지정해 주세요."
                    )
                }
                "NO_DATE" -> {
                    if (loginRequired) {
                        listener.onLog("달력에 해당 날짜가 없습니다. 로그인 여부도 확인해 주세요.")
                    } else {
                        listener.onLog("달력에 해당 날짜가 없습니다. 아직 예약이 열리지 않았을 수 있습니다.")
                    }
                    retryLater()
                }
                else -> {
                    listener.onLog("확인 중 오류: " + (obj?.optString("message") ?: "결과를 읽지 못했습니다."))
                    retryLater()
                }
            }
        }
    }

    private fun openReady() {
        if (!running) return
        if (scheduleNo.isBlank() || scheduleNo == "0") {
            listener.onLog("예약 페이지 번호를 얻지 못했습니다. 다시 확인합니다.")
            retryLater()
            return
        }
        phase = Phase.PREPARING
        readyRetries = 0
        listener.onStatus("예약 페이지 이동")
        load(Sunsang24.readyUrl(settings.subdomain, scheduleNo))
    }

    private fun prepare() {
        if (!running) return
        val script = Sunsang24Automation.prepareReady(settings.people, settings.bookerName, settings.phone)
        evaluate(script) { obj ->
            if (!running || phase != Phase.PREPARING) return@evaluate
            when (obj?.optString("status").orEmpty()) {
                "READY" -> {
                    val whenText = obj?.optString("when").orEmpty()
                    val deposit = obj?.optString("deposit").orEmpty()
                    listener.onLog("예약 정보 입력 완료 · " + whenText + " · 예약금 " + deposit + "원")
                    if (settings.autoConfirm) {
                        submit()
                    } else {
                        listener.onStatus("예약 직전에서 멈췄습니다")
                        listener.onLog("'자동 최종 확인'이 꺼져 있어 예약 버튼을 누르지 않았습니다.")
                        Notifier.alert(
                            webView.context,
                            "예약 직전!",
                            "화면에서 '예약하기'만 누르면 접수됩니다. (" + settings.date + " " + settings.people + "명)",
                            2002
                        )
                        stopInternal(
                            false,
                            "화면에서 '예약하기'를 눌러 주세요. (예약 버튼 직전에서 멈췄습니다)",
                            keepWebViewOpen = true
                        )
                    }
                }
                "LOGIN_REQUIRED" ->
                    stopInternal(false, "로그인이 풀려 '로그인 후 예약하기'만 보입니다. 카카오 로그인을 다시 해 주세요.")
                "PEOPLE_MISMATCH" -> {
                    val got = obj?.optInt("people", 0) ?: 0
                    stopInternal(false, "요청한 " + settings.people + "명을 선택하지 못했습니다(현재 " + got + "명). 남은 자리가 부족한 것 같습니다.")
                }
                "IMMINENT_AGREE" -> {
                    listener.onLog("출항 임박 안내 창을 닫았습니다. 계속 진행합니다.")
                    handler.postDelayed({ prepare() }, 800L)
                }
                "NOT_READY", "NO_BUTTON" -> {
                    readyRetries++
                    if (readyRetries > 8) {
                        stopInternal(false, "예약 페이지 구조를 알아보지 못했습니다. 사이트가 개편되었을 수 있습니다.")
                    } else {
                        handler.postDelayed({ prepare() }, 1_200L)
                    }
                }
                else -> {
                    listener.onLog("예약 페이지 확인 오류: " + (obj?.optString("message") ?: "결과를 읽지 못했습니다."))
                    retryLater()
                }
            }
        }
    }

    private fun submit() {
        phase = Phase.CONFIRMING
        listener.onStatus("예약 시도 중")
        listener.onLog("예약 버튼을 누릅니다. 확인 창은 자동으로 승인합니다.")
        Notifier.alert(
            webView.context,
            "예약 시도",
            settings.date + " " + settings.people + "명 예약을 접수합니다.",
            2003
        )
        evaluate(Sunsang24Automation.clickPay()) { obj ->
            if (obj?.optString("status") == "NO_BUTTON") {
                stopInternal(false, "예약 버튼을 찾지 못했습니다.")
                return@evaluate
            }
            // 버튼을 눌렀는데 "출항 임박 안내" 창이 먼저 뜨는 경우를 대비합니다.
            handler.postDelayed({ nudgeImminentModal() }, 1_500L)
        }
        // 응답이 계속 없으면 무한 대기에 빠지지 않도록 정리합니다.
        handler.postDelayed({
            if (running && phase == Phase.CONFIRMING) {
                stopInternal(false, "예약 요청에 대한 응답이 없습니다. 화면에서 직접 확인해 주세요.", keepWebViewOpen = true)
            }
        }, SUBMIT_TIMEOUT_MS)
    }

    private fun nudgeImminentModal() {
        if (!running || phase != Phase.CONFIRMING) return
        evaluate(Sunsang24Automation.clickImminentAgree()) { obj ->
            if (obj?.optString("status") == "AGREED") {
                listener.onLog("출항 임박 안내 창에 동의했습니다.")
            }
        }
    }

    private fun onBooked() {
        handler.removeCallbacks(pageTimeout)
        evaluate(Sunsang24Automation.readReservationDetail()) { obj ->
            val no = obj?.optString("reservationNo").orEmpty()
            val message = if (no.isNotBlank()) {
                "예약 접수 완료 · 예약번호 " + no + " (입금 기한을 확인해 주세요)"
            } else {
                "예약 접수 완료 · 예약번호는 마이페이지에서 확인해 주세요"
            }
            Notifier.alert(webView.context, "예약 완료!", message, 2004)
            stopInternal(true, message)
        }
    }

    // ───────────────────────────────────────────── 자바스크립트 대화상자 처리

    /**
     * confirm() 창을 자동으로 승인할지 결정합니다.
     * @return true/false 면 엔진이 결정한 것, null 이면 사용자가 직접 고르게 합니다.
     */
    fun answerConfirm(message: String): Boolean? {
        if (!running) return null
        val flat = message.replace("\n", " ")
        return if (phase == Phase.CONFIRMING && message.contains("예약")) {
            listener.onLog("확인 창 승인: " + flat)
            true
        } else {
            listener.onLog("확인 창을 자동 승인하지 않았습니다: " + flat)
            null
        }
    }

    /**
     * alert() 창을 자동으로 닫을지 결정합니다.
     * @return true 면 자동 닫기, null 이면 사용자에게 보여 줍니다.
     */
    fun answerAlert(message: String): Boolean? {
        if (!running) return null
        val flat = message.replace("\n", " ")
        listener.onLog("사이트 알림: " + flat)
        val failure = flat.contains("실패") || flat.contains("오류") || flat.contains("마감") ||
            flat.contains("불가") || flat.contains("부족") || flat.contains("인증")
        if (failure || phase == Phase.CONFIRMING) {
            stopInternal(false, "사이트 알림: " + flat)
        }
        return true
    }

    companion object {
        private const val PAGE_TIMEOUT_MS = 25_000L
        private const val SUBMIT_TIMEOUT_MS = 30_000L
    }
}
