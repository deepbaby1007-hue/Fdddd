package com.sunsang24.autobooking

/**
 * 화면(MainActivity)에서 실행 중인 자동 예약을 멈출 수 있게 연결해 주는 다리.
 * 실제 중지는 BookingWebViewActivity 가 맡습니다.
 */
object EngineBus {

    /** true 를 돌려주면 "실행 중인 작업을 멈췄다"는 뜻입니다. */
    var stopper: ((String) -> Boolean)? = null

    fun stop(reason: String): Boolean {
        val target = stopper ?: return false
        return target(reason)
    }
}
