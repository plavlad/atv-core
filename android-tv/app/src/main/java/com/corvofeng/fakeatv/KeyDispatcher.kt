package com.corvofeng.fakeatv

import android.content.Context
import android.util.Log

enum class InjectionMode(val key: String, val displayName: String) {
    AUTO("auto", "Автоадаптация (аппаратный -> ADB -> спецвозможности)"),
    EVENT("event", "Только аппаратный ввод (/dev/input)"),
    ADB("adb", "Только локальный ADB (127.0.0.1:5555)"),
    A11Y("a11y", "Только служба спецвозможностей (Accessibility)");

    companion object {
        fun fromKey(key: String): InjectionMode {
            return values().find { it.key.equals(key, ignoreCase = true) } ?: AUTO
        }
    }
}

object KeyDispatcher {
    private const val TAG = "KeyDispatcher"

    @Volatile
    var currentMode: InjectionMode = InjectionMode.AUTO

    @Volatile
    var menuBindingButton: String = "none"

    @Volatile
    var lastUsedEngine: String = "Не выполнено"
        private set

    fun init(context: Context) {
        val savedModeKey = DeviceInfo.getInjectionMode(context)
        currentMode = InjectionMode.fromKey(savedModeKey)
        menuBindingButton = DeviceInfo.getMenuButtonBinding(context)
        Log.i(TAG, "KeyDispatcher initialized: mode=$currentMode, menuBinding=$menuBindingButton")

        // Pre-warm local ADB connection in background if mode is AUTO or ADB
        if (currentMode == InjectionMode.AUTO || currentMode == InjectionMode.ADB) {
            LocalAdbDriver.init(context)
        }
    }

    fun setMode(context: Context, mode: InjectionMode) {
        currentMode = mode
        DeviceInfo.setInjectionMode(context, mode.key)
        Log.i(TAG, "InjectionMode updated to: $mode")
        if (mode == InjectionMode.AUTO || mode == InjectionMode.ADB) {
            LocalAdbDriver.init(context)
        }
    }

    fun dispatchButton(name: String): String {
        val lowerName = name.lowercase().trim()
        val isMappedToMenu = menuBindingButton.isNotBlank() &&
                menuBindingButton != "none" &&
                menuBindingButton.equals(lowerName, ignoreCase = true)

        var executedEngine = "none"

        when (currentMode) {
            InjectionMode.AUTO -> {
                // Tier 1: Hardware Event (/dev/input)
                if (RemoteController.isHardwareAvailable && RemoteController.sendKey(lowerName, isMappedToMenu)) {
                    executedEngine = "Аппаратный драйвер событий (/dev/input)"
                }
                // Tier 2: Local ADB (127.0.0.1:5555)
                else if (LocalAdbDriver.sendKey(lowerName, isMappedToMenu)) {
                    executedEngine = "Локальный ADB (127.0.0.1:5555)"
                }
                // Tier 3: Accessibility Service fallback
                else if (AtvAccessibilityService.instance?.sendKey(lowerName, isMappedToMenu) == true) {
                    executedEngine = "Служба спецвозможностей (Accessibility)"
                } else {
                    executedEngine = "Ошибка отправки (нет доступного канала)"
                }
            }

            InjectionMode.EVENT -> {
                if (RemoteController.sendKey(lowerName, isMappedToMenu)) {
                    executedEngine = "Аппаратный драйвер событий"
                } else {
                    executedEngine = "Ошибка аппаратного драйвера (нет прав или устройство отсутствует)"
                }
            }

            InjectionMode.ADB -> {
                if (LocalAdbDriver.sendKey(lowerName, isMappedToMenu)) {
                    executedEngine = "Локальный ADB"
                } else {
                    executedEngine = "Ошибка локального ADB (порт не подключен или закрыт)"
                }
            }

            InjectionMode.A11Y -> {
                if (AtvAccessibilityService.instance?.sendKey(lowerName, isMappedToMenu) == true) {
                    executedEngine = "Служба спецвозможностей"
                } else {
                    executedEngine = "Ошибка службы спецвозможностей (служба выключена)"
                }
            }
        }

        lastUsedEngine = executedEngine
        val display = if (isMappedToMenu) "$name -> MENU" else name
        Log.i(TAG, "dispatchButton: $display via [$executedEngine]")
        return executedEngine
    }
}
