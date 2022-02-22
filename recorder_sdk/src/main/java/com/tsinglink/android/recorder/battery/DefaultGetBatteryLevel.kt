package com.tsinglink.android.recorder.battery

import android.content.Context
import android.os.BatteryManager

class DefaultGetBatteryLevel(private val context: Context):IGetBatteryLevel {

    val manager: BatteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
    override fun getBatteryLevel(): Int {
        return manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
    }
}