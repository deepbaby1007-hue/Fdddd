package com.sunsang24.autobooking

import android.content.Context

/**
 * 앱에서 입력한 자동 예약 설정. SharedPreferences 에 저장해서 매번 다시 입력하지 않게 합니다.
 */
data class BookingSettings(
    val subdomain: String = "araonfishing",
    val shipName: String = "",
    val date: String = "",
    val people: Int = 2,
    val bookerName: String = "",
    val phone: String = "",
    val mode: Mode = Mode.OPENRUN,
    val openTime: String = "10:00:00",
    val intervalSec: Int = 5,
    val autoConfirm: Boolean = false
) {

    enum class Mode {
        /** 지정 시각 오픈런: 지정한 시각까지 기다렸다가 감시 시작 */
        OPENRUN,

        /** 빈자리 주기 감시: 지정한 간격으로 계속 확인 */
        WATCH
    }

    /** "2026-12-06" -> "202612" */
    val scheduleMonth: String
        get() = date.replace("-", "").take(6)

    /** 화면에 보여줄 요약 */
    fun summary(): String {
        val ship = if (shipName.isBlank()) "아무 배" else shipName
        val modeText = if (mode == Mode.OPENRUN) "오픈런 " + Sunsang24.prettyTime(openTime) else "감시 " + intervalSec + "초"
        return "$subdomain · $ship · $date · ${people}명 · $modeText"
    }

    fun save(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_SUBDOMAIN, subdomain)
            .putString(KEY_SHIP, shipName)
            .putString(KEY_DATE, date)
            .putInt(KEY_PEOPLE, people)
            .putString(KEY_NAME, bookerName)
            .putString(KEY_PHONE, phone)
            .putString(KEY_MODE, mode.name)
            .putString(KEY_OPEN_TIME, openTime)
            .putInt(KEY_INTERVAL, intervalSec)
            .putBoolean(KEY_AUTO_CONFIRM, autoConfirm)
            .apply()
    }

    companion object {
        /** 사이트에 부담을 주지 않기 위한 최소 감시 간격(초) */
        const val MIN_INTERVAL_SEC = 5

        private const val PREFS = "sunsang24_settings"
        private const val KEY_SUBDOMAIN = "subdomain"
        private const val KEY_SHIP = "shipName"
        private const val KEY_DATE = "date"
        private const val KEY_PEOPLE = "people"
        private const val KEY_NAME = "bookerName"
        private const val KEY_PHONE = "phone"
        private const val KEY_MODE = "mode"
        private const val KEY_OPEN_TIME = "openTime"
        private const val KEY_INTERVAL = "intervalSec"
        private const val KEY_AUTO_CONFIRM = "autoConfirm"

        private const val KEY_LAST_STATUS = "lastStatus"
        private const val KEY_LAST_LOG = "lastLog"

        fun load(context: Context): BookingSettings {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return BookingSettings(
                subdomain = p.getString(KEY_SUBDOMAIN, "araonfishing") ?: "araonfishing",
                shipName = p.getString(KEY_SHIP, "") ?: "",
                date = p.getString(KEY_DATE, "") ?: "",
                people = p.getInt(KEY_PEOPLE, 2),
                bookerName = p.getString(KEY_NAME, "") ?: "",
                phone = p.getString(KEY_PHONE, "") ?: "",
                mode = if (p.getString(KEY_MODE, Mode.OPENRUN.name) == Mode.WATCH.name) Mode.WATCH else Mode.OPENRUN,
                openTime = p.getString(KEY_OPEN_TIME, "10:00:00") ?: "10:00:00",
                intervalSec = p.getInt(KEY_INTERVAL, MIN_INTERVAL_SEC),
                autoConfirm = p.getBoolean(KEY_AUTO_CONFIRM, false)
            )
        }

        fun saveLastRun(context: Context, status: String, log: String) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_LAST_STATUS, status)
                .putString(KEY_LAST_LOG, log)
                .apply()
        }

        fun lastStatus(context: Context): String =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_LAST_STATUS, "") ?: ""

        fun lastLog(context: Context): String =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_LAST_LOG, "") ?: ""
    }
}
