package android.content;

import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.IInterface;
import android.os.RemoteException;

/**
 * 隐藏 API 的编译期占位符 —— IContentProvider。
 *
 * <p>移植自 refs/MAA-Meow/hidden-api/src/main/java/android/content/IContentProvider.java。
 * 真机上是 frameworks/base 的 AIDL 生成物 {@code android.content.IContentProvider}，
 * 公开 android.jar 里没有，所以只能自带等价签名。**只用于编译**，运行时不会被加载
 * （上层用 {@code compileOnly} 引用本模块）。
 *
 * <p>签名必须与真机一致：调用点是 {@code third/wrappers/ActivityManager.java:90} 的
 * {@code providerHolder.provider} 强转，以及 {@code third/FakeContext.java:105-129} 的
 * {@code ShellContentResolver} 覆写。多一个 / 少一个参数都会让 Binder transaction code 错位。
 */
public interface IContentProvider extends IInterface {

    Bundle call(String callingPkg, String method, String arg, Bundle extras) throws RemoteException;

    Bundle call(String callingPkg, String authority, String method, String arg, Bundle extras)
            throws RemoteException;

    Bundle call(String callingPkg, String attributionTag, String authority, String method, String arg,
                Bundle extras) throws RemoteException;

    Bundle call(AttributionSource attributionSource, String authority, String method, String arg,
                Bundle extras) throws RemoteException;

    abstract class Stub extends Binder implements IContentProvider {
        public static IContentProvider asInterface(IBinder obj) {
            throw new UnsupportedOperationException();
        }
    }
}
