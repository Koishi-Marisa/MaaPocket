package com.maafw.naruto.root

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Process

/**
 * Root 引擎 binder 回传 Provider（P1-5：ContentProvider 握手）。
 * root 进程（uid0/shell）经 getContentProviderExternal 调 call() 把引擎 binder 回传给 App，
 * 绕开 Android16 的 ServiceManager.getService 限制与 uid0 隐式广播过滤。
 */
class RootServiceBootstrapProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method != RootServiceBootstrapRegistry.METHOD_ATTACH_REMOTE_SERVICE || extras == null) {
            return super.call(method, arg, extras)
        }
        // 安全校验：只接受 shell(2000) / root(0) 调用方
        val callingUid = Binder.getCallingUid()
        if (callingUid != Process.SHELL_UID && callingUid != 0) return null

        val token = extras.getString(RootServiceBootstrapRegistry.KEY_TOKEN) ?: return null
        val binder = extras.getBinder(RootServiceBootstrapRegistry.KEY_SERVICE_BINDER) ?: return null
        val appBinder = RootServiceBootstrapRegistry.attach(token, binder) ?: return null

        return Bundle().apply {
            putBinder(RootServiceBootstrapRegistry.KEY_APP_BINDER, appBinder)
            putInt(RootServiceBootstrapRegistry.KEY_APP_PID, Process.myPid())
        }
    }

    override fun query(uri: Uri, projection: Array<String>?, selection: String?, selectionArgs: Array<String>?, sortOrder: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int = 0
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0
    override fun getType(uri: Uri): String? = null
}