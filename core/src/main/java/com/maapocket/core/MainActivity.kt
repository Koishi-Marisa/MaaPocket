package com.maapocket.core

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.maapocket.core.ui.AppRoot
import com.maapocket.core.ui.theme.MaaPocketTheme
import timber.log.Timber

/**
 * 唯一的 Activity。manifest 里 `com.maapocket.core.MainActivity` 是 LAUNCHER，
 * 三个 app-* 模块都直接复用 core 的这份声明。
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // MaaPocketApp 在 debug 里已经植过树了；这里只在「没有树」时补一次，
        // 否则每条日志会被打印两遍，反而更难读。
        // 注意：当前 Timber 版本里 `treeCount` 是 **属性** 不是函数，写成 `treeCount()`
        // 会得到 `Expression 'treeCount' of type 'Int' cannot be invoked as a function`。
        if (BuildConfig.DEBUG && Timber.treeCount == 0) {
            Timber.plant(Timber.DebugTree())
        }

        setContent {
            MaaPocketTheme {
                AppRoot()
            }
        }
    }
}
