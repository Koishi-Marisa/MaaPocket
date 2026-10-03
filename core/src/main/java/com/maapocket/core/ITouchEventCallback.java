package com.maapocket.core;

import android.os.RemoteException;

/**
 * 触控事件回调。
 *
 * <p>上游（MAA-Meow）里这是一个 AIDL：{@code app/src/main/aidl/com/aliothmoon/maameow/ITouchEventCallback.aidl}，
 * 因为它的跨进程通道是 binder。MaaPocket 的特权进程走的是**自建 socket 协议**
 * （见 {@code privilege/RemoteProtocol.kt} 与 {@code privilege/RemoteEngine.kt}），
 * 不需要 AIDL，也不需要 {@code Stub} / {@code asInterface}。所以这里退化成普通 Java 接口，
 * 但**方法签名逐字保持一致**，语义不变。
 *
 * <p>为什么仍要声明 {@code throws RemoteException}：调用点
 * {@code maa/InputControlUtils.java:111} 写的是
 * {@code catch (RemoteException | RuntimeException e)}。Java 不允许 catch 一个
 * 「方法体里不可能抛出」的受检异常，所以要么在这里声明它，要么改调用点。
 * 声明它更贴近上游语义（回调失败 = 对端没了，与 binder 死亡同质）。
 *
 * <p>参数含义与上游 AIDL 一致：
 * {@code onCallback(x, y, type, contact)} —— x/y 是事件坐标，type 是
 * {@code MotionEvent.getActionMasked()}，contact 是 {@code getPointerId()}。
 * 目前 MaaPocket 里只有 {@code InputControlUtils.setTouchCallback} 一个注册点，
 * 还没有实现者（触控预览走 {@code capture.preview} 的帧事件，不依赖这个回调）。
 */
public interface ITouchEventCallback {

    void onCallback(int x, int y, int type, int contact) throws RemoteException;
}
