package com.sunsang24.autobooking

import android.Manifest
import android.app.DatePickerDialog
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.snackbar.Snackbar
import com.sunsang24.autobooking.databinding.ActivityMainBinding
import java.util.Calendar
import java.util.Locale

/**
 * 설정 화면.
 *  - 선사·배·날짜·인원·예약자 정보·동작 방식을 입력받아 SharedPreferences 에 저장
 *  - "카카오 로그인 먼저 하기" / "자동 예약 시작" / "자동 예약 중지"
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted: Boolean ->
            if (!granted) {
                Toast.makeText(
                    this,
                    "알림 권한이 없으면 자리를 찾았을 때 알림이 표시되지 않습니다.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        Notifier.ensureChannel(this)
        askNotificationPermission()

        applySettings(BookingSettings.load(this))
        binding.rgMode.setOnCheckedChangeListener { _, _ -> updateModeFields() }
        updateModeFields()

        binding.etDate.setOnClickListener { showDatePicker() }

        binding.btnLogin.setOnClickListener { onLoginClick() }
        binding.btnStart.setOnClickListener { onStartClick() }
        binding.btnStop.setOnClickListener { onStopClick() }
    }

    override fun onResume() {
        super.onResume()
        val status = BookingSettings.lastStatus(this)
        val log = BookingSettings.lastLog(this)
        binding.tvLastStatus.text = status.ifBlank { "아직 실행한 적이 없습니다." }
        binding.tvLastLog.text = log.takeLast(1500)
    }

    // ─────────────────────────────────────────────────────── 설정 읽기/저장

    private fun applySettings(settings: BookingSettings) {
        binding.etSubdomain.setText(settings.subdomain)
        binding.etShipName.setText(settings.shipName)
        binding.etDate.setText(settings.date)
        binding.etPeople.setText(settings.people.toString())
        binding.etBookerName.setText(settings.bookerName)
        binding.etPhone.setText(settings.phone)
        binding.etOpenTime.setText(settings.openTime)
        binding.etInterval.setText(settings.intervalSec.toString())
        binding.swAutoConfirm.isChecked = settings.autoConfirm
        if (settings.mode == BookingSettings.Mode.WATCH) {
            binding.rbWatch.isChecked = true
        } else {
            binding.rbOpenrun.isChecked = true
        }
    }

    /** 입력값을 검사하고 설정 객체로 만듭니다. 잘못된 값이 있으면 안내 후 null */
    private fun collectSettings(): BookingSettings? {
        val subdomain = Sunsang24.normalizeSubdomain(binding.etSubdomain.text?.toString().orEmpty())
        if (!Sunsang24.isValidSubdomain(subdomain)) {
            warn("선사 서브도메인을 확인해 주세요. 예: araonfishing")
            return null
        }

        val date = binding.etDate.text?.toString()?.trim().orEmpty()
        if (!Regex("\\d{4}-\\d{2}-\\d{2}").matches(date) || Sunsang24.parseDateOrNull(date) == null) {
            warn("출조 날짜를 YYYY-MM-DD 형식으로 입력해 주세요. (예: 2026-12-06)")
            return null
        }

        val people = binding.etPeople.text?.toString()?.trim()?.toIntOrNull() ?: 0
        if (people !in 1..20) {
            warn("인원 수는 1~20명 사이로 입력해 주세요.")
            return null
        }

        val bookerName = binding.etBookerName.text?.toString()?.trim().orEmpty()
        if (bookerName.isBlank()) {
            warn("예약자명을 입력해 주세요.")
            return null
        }

        val phone = binding.etPhone.text?.toString()?.replace(Regex("[^0-9]"), "").orEmpty()
        if (phone.length < 10 || phone.length > 11) {
            warn("휴대전화 번호를 숫자만 10~11자리로 입력해 주세요.")
            return null
        }

        val mode = if (binding.rbWatch.isChecked) BookingSettings.Mode.WATCH else BookingSettings.Mode.OPENRUN

        val rawTime = binding.etOpenTime.text?.toString().orEmpty()
        val openTime = Sunsang24.normalizeTime(rawTime)
        if (mode == BookingSettings.Mode.OPENRUN && openTime == null) {
            warn("오픈 시각을 HH:mm:ss 형식으로 입력해 주세요. (예: 10:00:00)")
            return null
        }

        val interval = binding.etInterval.text?.toString()?.trim()?.toIntOrNull()
            ?: BookingSettings.MIN_INTERVAL_SEC
        if (interval < BookingSettings.MIN_INTERVAL_SEC) {
            warn("감시 간격은 ${BookingSettings.MIN_INTERVAL_SEC}초 이상으로 설정해 주세요.")
            return null
        }

        return BookingSettings(
            subdomain = subdomain,
            shipName = binding.etShipName.text?.toString()?.trim().orEmpty(),
            date = date,
            people = people,
            bookerName = bookerName,
            phone = phone,
            mode = mode,
            openTime = openTime ?: "10:00:00",
            intervalSec = interval,
            autoConfirm = binding.swAutoConfirm.isChecked
        )
    }

    // ──────────────────────────────────────────────────────────── 버튼 동작

    private fun onLoginClick() {
        val subdomain = Sunsang24.normalizeSubdomain(binding.etSubdomain.text?.toString().orEmpty())
        if (!Sunsang24.isValidSubdomain(subdomain)) {
            warn("먼저 선사 서브도메인을 입력해 주세요. 예: araonfishing")
            return
        }
        // 로그인 화면은 서브도메인·날짜만 있으면 되므로 그것만 저장합니다.
        BookingSettings.load(this).copy(
            subdomain = subdomain,
            shipName = binding.etShipName.text?.toString()?.trim().orEmpty(),
            date = binding.etDate.text?.toString()?.trim().orEmpty()
        ).save(this)

        startActivity(BookingWebViewActivity.createIntent(this, BookingWebViewActivity.MODE_LOGIN))
    }

    private fun onStartClick() {
        val settings = collectSettings() ?: return
        settings.save(this)

        if (!settings.autoConfirm) {
            Snackbar.make(
                binding.root,
                "예약 버튼 직전에서 멈추도록 설정되어 있습니다. (자동 접수는 스위치를 켜세요)",
                Snackbar.LENGTH_LONG
            ).show()
        }
        startActivity(BookingWebViewActivity.createIntent(this, BookingWebViewActivity.MODE_RUN))
    }

    private fun onStopClick() {
        val stopped = EngineBus.stop("사용자가 중지했습니다.")
        val message = if (stopped) "자동 예약을 멈췄습니다." else "지금 실행 중인 자동 예약이 없습니다."
        Snackbar.make(binding.root, message, Snackbar.LENGTH_SHORT).show()
    }

    // ───────────────────────────────────────────────────────────── 보조 기능

    private fun updateModeFields() {
        val watchMode = binding.rbWatch.isChecked
        binding.tilOpenTime.isEnabled = !watchMode
        binding.etOpenTime.isEnabled = !watchMode
        binding.etOpenTime.alpha = if (watchMode) 0.4f else 1f
    }

    private fun showDatePicker() {
        val calendar = Calendar.getInstance()
        Sunsang24.parseDateOrNull(binding.etDate.text?.toString()?.trim().orEmpty())?.let {
            calendar.time = it
        }
        DatePickerDialog(
            this,
            { _, year, month, day ->
                binding.etDate.setText(
                    String.format(Locale.ROOT, "%04d-%02d-%02d", year, month + 1, day)
                )
            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    private fun warn(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
