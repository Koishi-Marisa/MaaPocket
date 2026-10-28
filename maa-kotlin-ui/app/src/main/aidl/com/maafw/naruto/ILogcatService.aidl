// ILogcatService.aidl
package com.maafw.naruto;

interface ILogcatService {
    // 开始按 pid 抓取 logcat（appPid=App 进程，引擎进程由服务内 pgrep 定位），输出到 userDir/debug/logcat/
    void startCapture(int appPid, String userDir);

    // 停止抓取
    void stopCapture();
}