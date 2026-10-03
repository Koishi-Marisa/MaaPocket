package com.android.internal.app;

import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.os.RemoteException;

/**
 * 隐藏 API 的编译期占位符 —— IAppOpsService（appops 模式设置）。
 *
 * <p>移植自 refs/MAA-Meow/hidden-api/src/main/java/com/android/internal/app/IAppOpsService.java。
 * 只用于编译（上层 {@code compileOnly} 引用），运行时走设备上的真实现。
 * 注意包名是 {@code com.android.internal.app}，这不是笔误 —— 真机上的类就在那里。
 */
public interface IAppOpsService extends IInterface {

    void setMode(int code, int uid, String packageName, int mode) throws RemoteException;

    void setUidMode(int code, int uid, int mode) throws RemoteException;

    int checkOperation(int code, int uid, String packageName) throws RemoteException;

    abstract class Stub extends Binder implements IAppOpsService {

        public static IAppOpsService asInterface(IBinder obj) {
            throw new UnsupportedOperationException();
        }
    }
}
