package android.content.pm;

import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.os.RemoteException;

/**
 * 隐藏 API 的编译期占位符 —— IPackageManager。
 *
 * <p>移植自 refs/MAA-Meow/hidden-api/src/main/java/android/content/pm/IPackageManager.java。
 * 只用于编译（上层 {@code compileOnly} 引用），运行时走设备上的真实现。
 */
public interface IPackageManager extends IInterface {

    void grantRuntimePermission(String packageName, String permissionName, int userId) throws RemoteException;

    void revokeRuntimePermission(String packageName, String permissionName, int userId) throws RemoteException;

    int getPermissionFlags(String permissionName, String packageName, int userId) throws RemoteException;

    void updatePermissionFlags(String permissionName, String packageName, int flagMask, int flagValues, int userId) throws RemoteException;

    int checkPermission(String permName, String pkgName, int userId) throws RemoteException;

    abstract class Stub extends Binder implements IPackageManager {

        public static IPackageManager asInterface(IBinder obj) {
            throw new UnsupportedOperationException();
        }
    }
}
