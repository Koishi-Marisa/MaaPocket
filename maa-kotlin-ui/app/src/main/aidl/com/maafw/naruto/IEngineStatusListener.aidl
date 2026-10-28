// IEngineStatusListener.aidl
package com.maafw.naruto;

interface IEngineStatusListener {
    void onStatusChanged(boolean running, String currentEntry) = 1;

    void onLog(String message) = 2;

    // 脚本触摸事件（binder 直达，供触摸预览显示脚本点击位置）
    void onTouch(int action, int x, int y) = 3;

    // 任务链事件（分段进度用）：event = started / succeeded / failed
    void onTaskEvent(String entry, String event) = 4;
}