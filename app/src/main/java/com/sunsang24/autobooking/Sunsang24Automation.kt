package com.sunsang24.autobooking

/**
 * 선상24 페이지에 주입하는 자바스크립트 모음.
 *
 * ── 이 파일을 고쳐야 하는 경우 ────────────────────────────────────────────
 * 선상24가 홈페이지를 개편하면 아래 선택자(selector)가 바뀔 수 있습니다.
 * 2026년 11월 현재 실제 페이지에서 확인한 구조는 docs/선상24-페이지-구조.md 에
 * 그대로 적어 두었으니, 개편 후에는 브라우저 개발자 도구로 새 구조를 확인하고
 * 그 문서와 이 파일의 선택자를 함께 고치면 됩니다.
 *
 * ── 반환값 규칙 ──────────────────────────────────────────────────────────
 * 모든 스크립트는 마지막에 자바스크립트 객체({ status: ... })를 돌려주고,
 * Kotlin 쪽에서는 evaluateJavascript 콜백으로 그 JSON 을 받아 처리합니다.
 */
object Sunsang24Automation {

    /** 자바스크립트 문자열 리터럴로 안전하게 바꿔 줍니다. */
    private fun jsString(value: String): String {
        val sb = StringBuilder("\"")
        for (c in value) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '<' -> sb.append("\\u003c")
                '>' -> sb.append("\\u003e")
                '&' -> sb.append("\\u0026")
                else -> sb.append(c)
            }
        }
        sb.append("\"")
        return sb.toString()
    }

    /**
     * 출항일정 페이지(/ship/schedule_fleet/{YYYYMM})에서
     * 목표 날짜·배의 예약 가능 여부를 확인합니다.
     *
     * 돌려주는 status
     *  SEAT      : 원하는 인원이 탈 수 있는 자리가 있음 (scheduleNo 포함)
     *  NO_SEAT   : 배는 있으나 남은 자리가 부족함
     *  CLOSED    : 예약마감(또는 유선 예약만 가능)
     *  NO_SHIP   : 그 날짜에 해당 배가 없음 (배 이름이 틀렸거나 운항 없음)
     *  DEFAULT_FLOW : 그 배는 '기본 스케줄 선택' 방식이라 앱이 지원하지 않음
     *  NO_DATE   : 달력에 그 날짜가 없음 (월 이동 필요)
     *  ERROR     : 스크립트 오류
     */
    fun scanSchedule(dateIso: String, shipName: String, people: Int): String = """
(function () {
  try {
    var DATE = ${jsString(dateIso)};
    var SHIP = ${jsString(shipName)};
    var PEOPLE = $people;

    // 로그인 여부: 로그아웃 상태에서는 상단에 '로그인' 링크가 있습니다.
    var loginRequired = !!document.querySelector('#navbar a[href*="user/login"]');

    // 1) 날짜 블록 찾기. 실제 구조: <div id="d2026-12-06" class="shipsinfo_daywarp ...">
    var blocks = document.querySelectorAll('.ship_info_warp[data-sdate="' + DATE + '"]');
    if (!blocks || blocks.length === 0) {
      var dayEl = document.getElementById("d" + DATE);
      if (dayEl) { blocks = dayEl.querySelectorAll(".ship_info_warp"); }
    }
    if (!blocks || blocks.length === 0) {
      return { status: "NO_DATE", login: loginRequired };
    }

    var best = null;
    var found = null;

    for (var i = 0; i < blocks.length; i++) {
      var b = blocks[i];

      // 배 이름: <li class="title"><span class="modal-ship-detail">아라온피싱호</span></li>
      var titleEl = b.querySelector(".title .modal-ship-detail");
      if (!titleEl) { titleEl = b.querySelector(".title"); }
      var name = titleEl ? (titleEl.textContent || "").trim() : "";
      if (SHIP && name.indexOf(SHIP) < 0) { continue; }

      // 예약마감 표시: <span class="shipping_status" data-status_code="END">예약마감</span>
      var closedEl = b.querySelector('.shipping_status[data-status_code="END"]');

      // 남은자리: <strong>남은자리 : <span class="number blink_me">17명</span> ...
      var remainEl = b.querySelector(".seat .blink_me");
      var remain = -1;
      if (remainEl && remainEl.textContent) {
        remain = parseInt(remainEl.textContent.replace(/[^0-9]/g, ""), 10);
      }
      if (isNaN(remain)) { remain = -1; }

      // 바로예약 버튼: <button class="... btn_user_reservation" data-schedule_no="1586321" data-ship_status_code="ING">바로예약</button>
      var btn = b.querySelector("button.btn_user_reservation");
      var scheduleNo = b.getAttribute("data-schedule_no") || "";
      if (btn && btn.getAttribute("data-schedule_no")) {
        scheduleNo = btn.getAttribute("data-schedule_no");
      }

      var info = {
        ship: name,
        remain: remain,
        scheduleNo: String(scheduleNo),
        closed: !!closedEl,
        bookable: !!btn
      };

      if (!info.closed && info.bookable && (info.scheduleNo === "0" || info.scheduleNo === "")) {
        // 이 배는 '기본 스케줄 선택' 방식(/mypage/reservation_default_ready/...)이라 앱이 지원하지 않습니다.
        return { status: "DEFAULT_FLOW", login: loginRequired, ship: info.ship };
      }
      if (info.closed || !info.bookable) {
        if (!best) { best = info; }
        continue;
      }
      if (info.remain >= 0 && info.remain < PEOPLE) {
        if (!best || best.remain < info.remain) { best = info; }
        continue;
      }
      found = info;
      break;
    }

    if (found) {
      return {
        status: "SEAT",
        login: loginRequired,
        ship: found.ship,
        remain: found.remain,
        scheduleNo: found.scheduleNo
      };
    }
    if (best) {
      return {
        status: best.closed ? "CLOSED" : "NO_SEAT",
        login: loginRequired,
        ship: best.ship,
        remain: best.remain
      };
    }
    return { status: "NO_SHIP", login: loginRequired };
  } catch (e) {
    return { status: "ERROR", message: String(e) };
  }
})()
""".trimIndent()

    /**
     * 예약 진행 페이지(/mypage/reservation_ready/{no})에서
     * 인원·예약자명·휴대전화·약관동의를 채웁니다. 예약 버튼은 누르지 않습니다.
     *
     * 돌려주는 status
     *  READY            : 입력 완료. 바로 예약 버튼을 눌러도 됨
     *  LOGIN_REQUIRED   : 로그인이 풀려 '로그인 후 예약하기'만 보이는 상태
     *  PEOPLE_MISMATCH  : 남은 자리보다 인원이 많아 요청 인원을 채우지 못함
     *  IMMINENT_AGREE   : 출항 임박 안내 창을 닫았음. 잠시 후 다시 호출 필요
     *  NOT_READY        : 아직 페이지가 다 그려지지 않음 (잠시 후 재시도)
     *  NO_BUTTON        : 예약 버튼을 찾지 못함 (구조 변경 의심)
     *  ERROR            : 스크립트 오류
     */
    fun prepareReady(people: Int, bookerName: String, phone: String): String = """
(function () {
  try {
    var PEOPLE = $people;
    var NAME = ${jsString(bookerName)};
    var PHONE = ${jsString(phone.replace(Regex("[^0-9]"), ""))};

    // 값 입력 헬퍼 (사이트가 jQuery 로 값을 읽으므로 input/change 이벤트를 직접 발생시킵니다)
    function fire(el, value) {
      var proto = (el.tagName === "TEXTAREA") ? window.HTMLTextAreaElement.prototype
                                             : window.HTMLInputElement.prototype;
      var desc = Object.getOwnPropertyDescriptor(proto, "value");
      if (desc && desc.set) { desc.set.call(el, value); } else { el.value = value; }
      el.dispatchEvent(new Event("input", { bubbles: true }));
      el.dispatchEvent(new Event("change", { bubbles: true }));
    }

    // 1) 예약 버튼 찾기 (로그인 상태면 #btn_payment, 아니면 <a class="btn_payment">로그인 후 예약하기</a>)
    var payBtn = document.getElementById("btn_payment");
    if (!payBtn) { payBtn = document.querySelector("a.btn_payment"); }
    if (!payBtn) { return { status: "NOT_READY" }; }
    if (payBtn.tagName === "A" && (payBtn.textContent || "").indexOf("로그인") >= 0) {
      return { status: "LOGIN_REQUIRED" };
    }

    // 2) 출항 임박(내일·당일) 안내 창이 떠 있으면 '동의함'을 누릅니다.
    var imm = document.getElementById("modal_imminent_departure");
    if (imm && imm.className && (" " + imm.className + " ").indexOf(" in ") >= 0) {
      var agreeBtn = document.getElementById("btn_imminent_departure_agree");
      if (agreeBtn) { agreeBtn.click(); return { status: "IMMINENT_AGREE" }; }
    }

    // 3) 인원 맞추기: <div class="user_count"><a class="minus"> <span class="people_count">1</span> <a class="plus"> <input hidden name="person_count">
    var box = document.querySelector("div.user_count");
    if (!box) { return { status: "NOT_READY" }; }
    var plus = box.querySelector("a.plus");
    var minus = box.querySelector("a.minus");
    var cntEl = box.querySelector(".people_count");
    var peopleNow = 0;
    var guard = 0;
    while (guard++ < 40) {
      peopleNow = parseInt(((cntEl ? cntEl.textContent : "0") || "0").replace(/[^0-9]/g, ""), 10);
      if (isNaN(peopleNow)) { peopleNow = 0; }
      if (peopleNow === PEOPLE) { break; }
      if (peopleNow < PEOPLE) { if (plus) { plus.click(); } else { break; } }
      else { if (minus) { minus.click(); } else { break; } }
    }
    var hidden = document.querySelector("input[name='person_count']");
    if (hidden && parseInt(hidden.value, 10) !== PEOPLE) { fire(hidden, String(PEOPLE)); }

    // 4) 예약자명 / 휴대전화 (이미 채워져 있으면 회원정보를 그대로 둡니다)
    var nameInput = document.querySelector("input[name='name']");
    if (nameInput && NAME && (nameInput.value || "") === "") { fire(nameInput, NAME); }

    var p1 = document.querySelector("input[name='phone1']");
    if (p1 && PHONE.length >= 9) {
      var p2 = document.querySelector("input[name='phone2']");
      var p3 = document.querySelector("input[name='phone3']");
      var part1 = PHONE.substring(0, 3);
      var part2 = PHONE.substring(3, PHONE.length - 4);
      var part3 = PHONE.substring(PHONE.length - 4);
      if ((p1.value || "") === "") { fire(p1, part1); }
      if (p2 && (p2.value || "") === "") { fire(p2, part2); }
      if (p3 && (p3.value || "") === "") { fire(p3, part3); }
    }

    // 5) 약관 동의 (필수 3개 이상). name="agree_rule[]" 를 모두 체크합니다.
    var rules = document.querySelectorAll("input[name='agree_rule[]']");
    var i;
    for (i = 0; i < rules.length; i++) { if (!rules[i].checked) { rules[i].click(); } }
    var checkedCount = 0;
    for (i = 0; i < rules.length; i++) { if (rules[i].checked) { checkedCount++; } }
    if (checkedCount < 3) {
      var all = document.querySelector("input[name='agree_all']") || document.querySelector("input#agree_all");
      if (all && !all.checked) { all.click(); }
      var checks = document.querySelectorAll("input.user_check");
      for (i = 0; i < checks.length; i++) { if (!checks[i].checked) { checks[i].click(); } }
      checkedCount = 0;
      for (i = 0; i < rules.length; i++) { if (rules[i].checked) { checkedCount++; } }
    }

    // 6) 결과 요약 읽기
    var totalEl = document.querySelector(".total_price");
    var depositEl = document.querySelector(".deposit_price");
    var whenEl = document.querySelector(".schedule_sdate_time");

    if (peopleNow !== PEOPLE) {
      return { status: "PEOPLE_MISMATCH", people: peopleNow, remain: peopleNow };
    }

    return {
      status: "READY",
      people: peopleNow,
      total: totalEl ? (totalEl.textContent || "").trim() : "",
      deposit: depositEl ? (depositEl.textContent || "").trim() : "",
      when: whenEl ? (whenEl.textContent || "").trim() : "",
      agreeChecked: checkedCount
    };
  } catch (e) {
    return { status: "ERROR", message: String(e) };
  }
})()
""".trimIndent()

    /**
     * 예약 버튼을 누릅니다. 버튼을 누르면 사이트가 confirm() 창을 띄우는데,
     * 그 창은 BookingWebViewActivity 의 onJsConfirm 이 처리합니다.
     */
    fun clickPay(): String = """
(function () {
  try {
    var btn = document.getElementById("btn_payment");
    if (!btn) { return { status: "NO_BUTTON" }; }
    btn.click();
    return { status: "CLICKED" };
  } catch (e) {
    return { status: "ERROR", message: String(e) };
  }
})()
""".trimIndent()

    /**
     * 예약 완료 페이지(/mypage/reservation_detail/{no})에서 예약번호와 요약을 읽습니다.
     */
    fun readReservationDetail(): String = """
(function () {
  try {
    var text = document.body ? (document.body.innerText || "") : "";
    var flat = text.replace(/\s+/g, " ").trim();
    var no = "";
    var m = flat.match(/예약번호[^0-9]{0,12}([0-9]{5,})/);
    if (m) { no = m[1]; }
    if (!no) {
      var m2 = location.pathname.match(/reservation_detail\/([0-9]+)/);
      if (m2) { no = m2[1]; }
    }
    return { status: "DETAIL", reservationNo: no, text: flat.substring(0, 400) };
  } catch (e) {
    return { status: "ERROR", message: String(e) };
  }
})()
""".trimIndent()

    /**
     * 출항 임박 안내 창이 떠 있으면 '동의함'을 눌러 줍니다.
     * (예약 버튼을 누른 뒤에 이 창이 뜨는 경우가 있어 예약 시도 후에도 한 번 더 확인합니다)
     */
    fun clickImminentAgree(): String = """
(function () {
  try {
    var imm = document.getElementById("modal_imminent_departure");
    if (imm && imm.className && (" " + imm.className + " ").indexOf(" in ") >= 0) {
      var agree = document.getElementById("btn_imminent_departure_agree");
      if (agree) { agree.click(); return { status: "AGREED" }; }
      return { status: "VISIBLE" };
    }
    return { status: "NONE" };
  } catch (e) {
    return { status: "ERROR", message: String(e) };
  }
})()
""".trimIndent()

    /**
     * 로그인 화면에서 '카카오 아이디로 로그인' 버튼을 찾아주는 안내용 스크립트.
     * (자동 클릭은 하지 않습니다. 카카오 인증은 사용자가 직접 해야 합니다.)
     */
    fun findKakaoButton(): String = """
(function () {
  try {
    var links = document.querySelectorAll("a, button");
    for (var i = 0; i < links.length; i++) {
      var t = (links[i].textContent || "").replace(/\s+/g, "");
      if (t.indexOf("카카오") >= 0) { return { found: true, text: t.substring(0, 40) }; }
    }
    return { found: false };
  } catch (e) {
    return { found: false };
  }
})()
""".trimIndent()
}
