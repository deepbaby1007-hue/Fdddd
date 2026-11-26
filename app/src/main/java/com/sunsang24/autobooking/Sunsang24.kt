package com.sunsang24.autobooking

import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 선상24 URL 규칙과 날짜/시간 계산을 모아 둔 곳입니다.
 *
 * 실제 사이트에서 확인한 규칙 (2026-11 기준):
 *  - 선사별 주소      : https://{서브도메인}.sunsang24.com
 *  - 출항일정(월별)   : /ship/schedule_fleet/{YYYYMM}
 *  - 예약 진행 페이지 : /mypage/reservation_ready/{schedule_no}
 *  - 로그인 페이지    : https://app.sunsang24.com/user/login?goto={돌아올 주소}
 *
 * 사이트가 개편되면 이 파일과 Sunsang24Automation.kt 만 손보면 됩니다.
 */
object Sunsang24 {

    const val LOGIN_URL = "https://app.sunsang24.com/user/login"

    /** 서브도메인은 영문/숫자/하이픈만 허용합니다. (예: araonfishing) */
    private val SUBDOMAIN_PATTERN = Regex("^[a-z0-9][a-z0-9-]{1,40}$")

    /** 사용자가 "https://araonfishing.sunsang24.com/..." 처럼 붙여넣어도 서브도메인만 뽑아냅니다. */
    fun normalizeSubdomain(raw: String): String {
        var value = raw.trim().lowercase(Locale.ROOT)
        value = value.removePrefix("https://").removePrefix("http://")
        value = value.substringBefore('/')
        value = value.substringBefore('.')
        return value.trim()
    }

    fun isValidSubdomain(value: String): Boolean = SUBDOMAIN_PATTERN.matches(value)

    fun baseUrl(subdomain: String): String = "https://$subdomain.sunsang24.com"

    /** 예: dateIso = "2026-12-06" -> https://araonfishing.sunsang24.com/ship/schedule_fleet/202612 */
    fun scheduleUrl(subdomain: String, dateIso: String): String {
        val month = dateIso.replace("-", "").take(6)
        return baseUrl(subdomain) + "/ship/schedule_fleet/" + month
    }

    /** 예: scheduleNo = "1586321" -> https://araonfishing.sunsang24.com/mypage/reservation_ready/1586321 */
    fun readyUrl(subdomain: String, scheduleNo: String): String =
        baseUrl(subdomain) + "/mypage/reservation_ready/" + scheduleNo

    /** 카카오 로그인 후 원래 보던 페이지로 돌아오게 하는 로그인 주소 */
    fun loginUrl(gotoUrl: String): String =
        LOGIN_URL + "?goto=" + URLEncoder.encode(gotoUrl, "UTF-8")

    fun todayIso(): String = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date())

    fun parseDateOrNull(iso: String): Date? = try {
        val format = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT)
        format.isLenient = false
        format.parse(iso)
    } catch (e: Exception) {
        null
    }

    /** "HH:mm" 또는 "HH:mm:ss" 를 "HH:mm:ss" 로 정리합니다. 형식이 아니면 null */
    fun normalizeTime(raw: String): String? {
        val parts = raw.trim().split(":")
        if (parts.size < 2 || parts.size > 3) return null
        val hour = parts[0].toIntOrNull() ?: return null
        val minute = parts[1].toIntOrNull() ?: return null
        val second = if (parts.size == 3) (parts[2].toIntOrNull() ?: return null) else 0
        if (hour !in 0..23 || minute !in 0..59 || second !in 0..59) return null
        return String.format(Locale.ROOT, "%02d:%02d:%02d", hour, minute, second)
    }

    /** 오늘 날짜 기준으로 timeHms 까지 남은 밀리초. 이미 지났으면 0 이하 */
    fun millisUntil(timeHms: String): Long {
        val parts = timeHms.split(":")
        val hour = parts.getOrNull(0)?.toIntOrNull() ?: 0
        val minute = parts.getOrNull(1)?.toIntOrNull() ?: 0
        val second = parts.getOrNull(2)?.toIntOrNull() ?: 0

        val now = Calendar.getInstance()
        val nowMillis = now.timeInMillis

        val target = Calendar.getInstance()
        target.set(Calendar.HOUR_OF_DAY, hour)
        target.set(Calendar.MINUTE, minute)
        target.set(Calendar.SECOND, second)
        target.set(Calendar.MILLISECOND, 0)

        return target.timeInMillis - nowMillis
    }

    /** "HH:mm:ss" 를 사람이 읽기 좋은 형태로 */
    fun prettyTime(timeHms: String): String = timeHms.split(":").take(2).joinToString(":")
}
