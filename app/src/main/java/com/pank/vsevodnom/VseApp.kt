package com.pank.vsevodnom

import android.app.Application

/**
 * Приложение «Всё в одном». В процессе клавиатуры (`:ime`) запускается только клавиатура (Inkwell/FlorisBoard),
 * всё остальное приложение там не нужно. В основном процессе ничего особого не делаем.
 */
class VseApp : Application() {
    override fun onCreate() {
        super.onCreate()
        com.pank.vsevodnom.data.CrashLog.install(this)
        if (getProcessName().endsWith(":ime")) com.goodwy.keyboard.FlorisApplication.install(this)
    }
}
