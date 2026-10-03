package com.maapocket.core.privilege;

/**
 * 特权通道「引导阶段」的常量表：ContentProvider authority、方法名、Bundle 键。
 *
 * <h2>为什么不能再用 LocalSocket</h2>
 *
 * <p>MaaPocket 最初把传输层做成抽象命名空间的 {@code LocalSocket}（app 当客户端、特权进程当
 * 服务端），协议是自描述的 JSON 行。真机上（HONOR AGI-AN00 / Android 15 / 未 root /
 * Shizuku 以 shell(2000) 运行）必然失败：
 *
 * <pre>
 * PrivilegedSession: connect failed: ... Caused by: java.io.IOException: Permission denied
 *     at android.net.LocalSocketImpl.connectLocal(Native Method)
 *
 * avc: denied { connectto } for path=006D6161706F636B65742E... \
 *     scontext=u:r:untrusted_app:s0:c41,c257,c512,c768 tcontext=u:r:shell:s0 \
 *     tclass=unix_stream_socket permissive=0
 * </pre>
 *
 * <p>也就是说：app 跑在 {@code untrusted_app} 域、特权进程跑在 {@code shell} 域，
 * SELinux 的 {@code connectto} 规则**根本不允许**前者连后者建的 unix stream socket，
 * 而且 {@code permissive=0}（Enforcing）。这不是权限申请能解决的问题，换名字、换 namespace
 * 都没用。{@code getenforce} 在真机上是 {@code Enforcing}。
 *
 * <h2>替代方案：ContentProvider 引导 + binder 传管道</h2>
 *
 * <p>方向反过来就行：**app 提供 ContentProvider（app 自己的域，谁都能按 authority 找），
 * 特权进程主动调进来**，顺手把两条单向管道 FD 交给我们。
 *
 * <ol>
 *   <li>app 侧 {@code BootstrapRegistry.register(token)} 先挂一个槽位（**必须早于 spawn**，
 *       否则特权进程调回来时找不到 token）；</li>
 *   <li>app spawn 特权进程；</li>
 *   <li>特权进程 {@code ParcelFileDescriptor.createPipe()} 建两条管道，
 *       用 {@code getContentProviderExternal} 拿到本 provider，调 {@link #METHOD_ATTACH}
 *       把「我们该读的那端」和「我们该写的那端」放进 Bundle 传回来；</li>
 *   <li>本 provider 校验 calling uid 必须是 shell(2000) 或 root(0)，然后把 FD 交给
 *       {@code BootstrapRegistry}，唤醒第 1 步的槽位。</li>
 * </ol>
 *
 * <p>FD 经 binder 传递时内核会 dup，所以双方各持有独立的 fd，谁先关都不影响另一端。
 *
 * <h2>管道方向约定</h2>
 *
 * <p>两个键都是从 **app 视角**命名的（app 是接收方，这样读代码不需要在脑子里换位）：
 * <ul>
 *   <li>{@link #KEY_FROM_REMOTE} — 「来自特权进程」= 特权进程**写**、app **读**。
 *       特权进程保留 pipe A 的 write 端，把 read 端送过来。</li>
 *   <li>{@link #KEY_TO_REMOTE} — 「发给特权进程」= app **写**、特权进程**读**。
 *       特权进程保留 pipe B 的 read 端，把 write 端送过来。</li>
 * </ul>
 *
 * <p>送出之后，特权进程必须立刻关掉自己手上多出来的那一端（pipe A 的 read 端、
 * pipe B 的 write 端），否则管道永远等不到 EOF，「对端已退出」这件事就检测不出来。
 *
 * <p>放在 Java 里是因为它同时被 Kotlin（{@code BootstrapRegistry}/{@code BootstrapProvider}）
 * 和裸 {@code app_process} 里的 Java（{@code BootstrapClient}/{@code RemoteMain}）引用，
 * 纯常量 holder 用 Java 两边都不需要 {@code @JvmField} / {@code const} 的互操作体操。
 */
public final class BootstrapProtocol {

    /** 本 provider 的 authority 后缀；完整 authority = {@code <applicationId> + 本值}。 */
    public static final String AUTHORITY_SUFFIX = ".maapocket.bootstrap";

    /** {@code ContentProvider.call()} 的方法名，语义是「把管道的 app 端交给我」。 */
    public static final String METHOD_ATTACH = "attachRemoteStream";

    /** Bundle 键：本次会话的一次性 token，app 用它把 FD 派发到正确的槽位。 */
    public static final String KEY_TOKEN = "token";

    /** Bundle 键：特权进程写、app 读的那个 FD。 */
    public static final String KEY_FROM_REMOTE = "from_remote";

    /** Bundle 键：app 写、特权进程读的那个 FD。 */
    public static final String KEY_TO_REMOTE = "to_remote";

    /** Bundle 键：特权进程的 pid（诊断用）。 */
    public static final String KEY_REMOTE_PID = "remote_pid";

    /** Bundle 键：特权进程的 uid（诊断用；应该是 2000 或 0）。 */
    public static final String KEY_REMOTE_UID = "remote_uid";

    /** Bundle 键（回包）：app 的 pid，特权进程拿去当看门狗判据。 */
    public static final String KEY_APP_PID = "app_pid";

    /** Bundle 键（回包）：app 的 uid。 */
    public static final String KEY_APP_UID = "app_uid";

    private BootstrapProtocol() {
        /* 纯常量 holder，不可实例化 */
    }
}
