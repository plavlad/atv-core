package com.corvofeng.fakeatv

import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.format.Formatter
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvIp: TextView
    private lateinit var tvPin: TextView
    private lateinit var tvAccessibility: TextView
    private lateinit var tvMenuBinding: TextView
    private lateinit var tvInjectionMode: TextView
    private lateinit var tvDriverStatus: TextView
    private lateinit var tvLog: TextView
    private lateinit var btnToggle: Button
    private lateinit var btnAccessibility: Button
    private lateinit var btnMenuBinding: Button
    private lateinit var btnInjectionMode: Button

    private val logEntries = mutableListOf<String>()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val type = intent?.getStringExtra(AtvService.EXTRA_EVENT_TYPE) ?: return
            val detail = intent.getStringExtra(AtvService.EXTRA_EVENT_DETAIL) ?: ""
            appendLog("[$type] $detail")
            updateUi()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        KeyDispatcher.init(this)

        tvStatus = findViewById(R.id.tvStatus)
        tvIp = findViewById(R.id.tvIp)
        tvPin = findViewById(R.id.tvPin)
        tvAccessibility = findViewById(R.id.tvAccessibility)
        tvMenuBinding = findViewById(R.id.tvMenuBinding)
        tvInjectionMode = findViewById(R.id.tvInjectionMode)
        tvDriverStatus = findViewById(R.id.tvDriverStatus)
        tvLog = findViewById(R.id.tvLog)
        btnToggle = findViewById(R.id.btnToggle)
        btnAccessibility = findViewById(R.id.btnAccessibility)
        btnMenuBinding = findViewById(R.id.btnMenuBinding)
        btnInjectionMode = findViewById(R.id.btnInjectionMode)

        btnMenuBinding.setOnClickListener {
            showMenuBindingDialog()
        }

        btnInjectionMode.setOnClickListener {
            showInjectionModeDialog()
        }

        btnToggle.setOnClickListener {
            if (AtvService.isRunning) {
                val intent = Intent(this, AtvService::class.java).apply {
                    action = AtvService.ACTION_STOP
                }
                startService(intent)
            } else {
                startAtvService()
            }
            btnToggle.postDelayed({ updateUi() }, 500)
        }

        btnAccessibility.setOnClickListener {
            if (RemoteController.isHardwareAvailable) {
                Toast.makeText(this, "Драйвер аппаратного пульта (/dev/input/event0) активен!\nСлужба специальных возможностей не требуется.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (AtvAccessibilityService.isServiceRunning) {
                Toast.makeText(this, "Служба специальных возможностей уже включена", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            try {
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                startActivity(intent)
            } catch (e: ActivityNotFoundException) {
                AlertDialog.Builder(this)
                    .setTitle("Настройки специальных возможностей")
                    .setMessage(
                        "На этом устройстве нет системной страницы настроек специальных возможностей.\n\n" +
                        "Включите через ADB:\n\n" +
                        "adb shell settings put secure enabled_accessibility_services com.corvofeng.fakeatv/.AtvAccessibilityService\n" +
                        "adb shell settings put secure accessibility_enabled 1"
                    )
                    .setPositiveButton("ОК", null)
                    .show()
            }
        }

        // Auto-start service on app launch
        if (!AtvService.isRunning) {
            startAtvService()
        }
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter(AtvService.BROADCAST_EVENT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
        }
        updateUi()
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(receiver)
    }

    private fun startAtvService() {
        val deviceName = DeviceInfo.getDeviceName(this)
        val intent = Intent(this, AtvService::class.java).apply {
            action = AtvService.ACTION_START
            putExtra(AtvService.EXTRA_NAME, deviceName)
            putExtra(AtvService.EXTRA_PIN, 1111)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun updateUi() {
        val running = AtvService.isRunning
        val deviceName = DeviceInfo.getDeviceName(this)
        val deviceId = DeviceInfo.getDeviceId(this)
        tvStatus.text = if (running) "$deviceName: Запущен" else "$deviceName: Остановлен"
        tvStatus.setTextColor(if (running) 0xFF4CAF50.toInt() else 0xFFF44336.toInt())
        btnToggle.text = if (running) "Остановить сервер" else "Запустить сервер"

        tvIp.text = "IP: ${getWifiIpAddress()}   ID: $deviceId"
        tvPin.text = "PIN для сопряжения: 1111"

        val currentBinding = DeviceInfo.getMenuButtonBinding(this)
        tvMenuBinding.text = "Назначение кнопки Menu: ${getMenuBindingDisplayName(currentBinding)}"

        // Injection mode display
        val mode = KeyDispatcher.currentMode
        tvInjectionMode.text = "Режим ввода кнопок: ${mode.displayName}"

        val hwOk = RemoteController.isHardwareAvailable
        val adbOk = LocalAdbDriver.isConnected.get()
        val accessOk = AtvAccessibilityService.isServiceRunning

        val hwStr = if (hwOk) "Аппаратный[●доступен]" else "Аппаратный[○нет]"
        val adbStr = if (adbOk) "ADB[●подключен]" else "ADB[○отключен]"
        val a11yStr = if (accessOk) "Спецвозможности[●готово]" else "Спецвозможности[○выкл]"
        val lastUsed = KeyDispatcher.lastUsedEngine

        tvDriverStatus.text = "Каналы: $hwStr | $adbStr | $a11yStr\nАктивный сейчас: $lastUsed"
        tvAccessibility.text = "Служба спецвозможностей: " + if (accessOk) "включена (резервный канал)" else "выключена (рекомендуется включить)"
        tvAccessibility.setTextColor(if (accessOk) 0xFF4CAF50.toInt() else 0xFFFF9800.toInt())
    }

    private fun showInjectionModeDialog() {
        val modes = InjectionMode.values()
        val options = modes.map { it.displayName }.toTypedArray()
        val current = KeyDispatcher.currentMode
        val currentIndex = modes.indexOf(current).let { if (it >= 0) it else 0 }

        AlertDialog.Builder(this)
            .setTitle("Выбор режима ввода кнопок (приоритет)")
            .setSingleChoiceItems(options, currentIndex) { dialog, which ->
                val selected = modes[which]
                KeyDispatcher.setMode(this, selected)
                appendLog("[Конфиг] Режим ввода кнопок: ${selected.displayName}")
                updateUi()
                Toast.makeText(this, "Переключено: ${selected.displayName}", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun showMenuBindingDialog() {
        val options = arrayOf(
            "Нет (по умолчанию: сохранить исходную функцию кнопки)",
            "Кнопка Воспроизведение/Пауза (play_pause, рекомендуется, внизу слева)",
            "Кнопка ТВ/Home (home, внизу справа)",
            "Кнопка Без звука (mute, вверху слева)",
            "Кнопка Назад (menu, снизу по центру)"
        )
        val values = arrayOf("none", "play_pause", "home", "mute", "menu")
        val current = DeviceInfo.getMenuButtonBinding(this)
        val currentIndex = values.indexOf(current).let { if (it >= 0) it else 0 }

        AlertDialog.Builder(this)
            .setTitle("Назначить кнопку пульта как Android Menu")
            .setSingleChoiceItems(options, currentIndex) { dialog, which ->
                val selected = values[which]
                DeviceInfo.setMenuButtonBinding(this, selected)
                RemoteController.menuBindingButton = selected
                AtvAccessibilityService.instance?.menuBindingButton = selected

                // Notify running AtvService
                val intent = Intent(this, AtvService::class.java).apply {
                    action = AtvService.ACTION_SET_MENU_BINDING
                    putExtra(AtvService.EXTRA_BUTTON, selected)
                }
                startService(intent)

                appendLog("[Конфиг] Назначение Menu: ${options[which]}")
                updateUi()
                Toast.makeText(this, "Установлено: ${options[which]}", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun getMenuBindingDisplayName(binding: String): String {
        return when (binding.lowercase()) {
            "play_pause" -> "Кнопка Воспроизведение/Пауза (play_pause)"
            "home" -> "Кнопка ТВ/Home (home)"
            "mute" -> "Кнопка Без звука (mute)"
            "menu" -> "Кнопка Назад (menu)"
            "none", "" -> "Нет (по умолчанию)"
            else -> binding
        }
    }

    private fun appendLog(line: String) {
        logEntries.add(0, line)
        if (logEntries.size > 15) {
            logEntries.removeLast()
        }
        tvLog.text = logEntries.joinToString("\n")
    }

    private fun getWifiIpAddress(): String {
        return DeviceInfo.getLocalIpAddress(this) ?: "Неизвестно"
    }
}
