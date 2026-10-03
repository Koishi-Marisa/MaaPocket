package android.os;

/**
 * 隐藏 API 的编译期占位符 —— IDeviceIdleController（Doze 白名单）。
 *
 * <p>移植自 refs/MAA-Meow/hidden-api/src/main/java/android/os/IDeviceIdleController.java。
 * 只用于编译（上层 {@code compileOnly} 引用），运行时走设备上的真实现。
 */
public interface IDeviceIdleController extends IInterface {

    void addPowerSaveWhitelistApp(String packageName) throws RemoteException;

    void removePowerSaveWhitelistApp(String packageName) throws RemoteException;

    boolean isPowerSaveWhitelistApp(String packageName) throws RemoteException;

    abstract class Stub extends Binder implements IDeviceIdleController {
        public static IDeviceIdleController asInterface(IBinder obj) {
            throw new UnsupportedOperationException();
        }
    }
}
