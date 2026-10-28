// IRemoteEngineService.aidl
package com.maafw.naruto;

import android.content.Intent;
import android.view.Surface;
import com.maafw.naruto.IEngineStatusListener;

interface IRemoteEngineService {
    void destroy() = 16777114;

    void exit() = 1;

    String version() = 2;

    boolean setup(String userDir) = 3;

    int startVirtualDisplay() = 4;

    void stopVirtualDisplay() = 5;

    void setMonitorSurface(in Surface surface) = 6;

    boolean startTask(String entry, String pipelineOverride) = 7;

    boolean startTasksJson(String json) = 8;

    void stopTask() = 9;

    boolean isRunning() = 10;

    String currentTask() = 11;

    boolean startActivity(in Intent intent) = 12;

    boolean startActivityOnDisplay(in Intent intent, int displayId) = 18;

    boolean isPackageInstalled(String packageName) = 13;

    boolean moveAppToVirtualDisplay(String packageName) = 14;

    void setDisplayPower(boolean on) = 15;

    void setPreviewEnabled(boolean enabled) = 29;

    void pauseTask() = 30;

    String captureFramePng(String dirPath) = 16;

    boolean stopPackage(String packageName) = 17;

    boolean setAudioMuted(boolean muted) = 19;

    boolean injectTouch(int action, int x, int y) = 20;

    // 多点触控注入：points 展平为 [x1,y1,x2,y2,...]，actionIndex 用于 POINTER_DOWN/UP 指定手指
    boolean injectMultiTouch(int action, in int[] points, int actionIndex) = 31;

    int[] getDisplayResolution() = 21;

    void hardwareScreenOff() = 22;

    boolean setResolution(int width, int height, int dpi) = 23;

    boolean injectKey(int keyCode) = 28;

    void registerStatusListener(IEngineStatusListener listener) = 25;

    void unregisterStatusListener(IEngineStatusListener listener) = 26;

    String captureLogcat(int lines) = 27;

    // 虚拟屏游戏真实渲染帧率（native 层按帧计数，debug 用）
    double getFps() = 32;

// Agent 独立进程是否已连接（FindToChallenge 等 Custom 节点走 agent 执行）
boolean isAgentConnected() = 34;

// 部署 libbridge.so：App 进程读取安装包内 so 字节，引擎写入 /data/local/tmp（data 分区可执行，绕开 FUSE noexec 与 /data/app 权限）
boolean deployBridge(in byte[] data) = 35;

    // 脚本识别频率（MaaFramework 每秒截图识别次数，判断脚本是否卡住）
    double getScriptFps() = 33;

    // ───────────── P0 守护与权限（20 号路线图 A1/A3/E5） ─────────────

    /** 心跳：App 绑定成功后喂 App pid；引擎看门狗每 5s 查 /proc/<pid>，App 死则引擎自杀（防孤儿引擎占虚拟屏） */
    void heartbeat(int appPid) = 36;

    /** 为指定包授予权限位：1=省电豁免 2=后台不受限 4=通知 8=悬浮窗 16=存储 32=无障碍（shell 身份代授） */
    int grantPermissions(String packageName, int permissions) = 37;

    /** 游戏进程存活探测：0=存活 1=死亡 2=未知（AppWatchdog 运行期守护用） */
    int isAppAlive(String packageName) = 38;

    /** 游戏是否仍在虚拟屏上（AppWatchdog 漂移检测用；无法判断/未创建虚拟屏时宽松返回 true） */
    boolean isAppOnVirtualDisplay(String packageName) = 39;
}