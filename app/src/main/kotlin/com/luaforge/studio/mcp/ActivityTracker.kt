package com.luaforge.studio.mcp

import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.lang.ref.WeakReference

/**
 * 追踪当前前台 Activity,供 MCP 运行时界面检查使用。
 *
 * 用 WeakReference 持有,避免 Activity 泄漏。MCP 服务与界面同进程,
 * 因此可直接读取界面树。
 */
object ActivityTracker : Application.ActivityLifecycleCallbacks {

    private var currentActivity = WeakReference<Activity>(null)
    private var resumedActivity = WeakReference<Activity>(null)

    @Volatile
    private var installed = false

    @Synchronized
    fun install(application: Application) {
        if (installed) return
        installed = true
        application.registerActivityLifecycleCallbacks(this)
    }

    /** 优先返回已 resumed 的 Activity,其次返回最近创建的。 */
    fun current(): Activity? = resumedActivity.get() ?: currentActivity.get()

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        currentActivity = WeakReference(activity)
    }

    override fun onActivityStarted(activity: Activity) {
        currentActivity = WeakReference(activity)
    }

    override fun onActivityResumed(activity: Activity) {
        currentActivity = WeakReference(activity)
        resumedActivity = WeakReference(activity)
    }

    override fun onActivityPaused(activity: Activity) {
        if (resumedActivity.get() === activity) {
            resumedActivity = WeakReference(null)
        }
    }

    override fun onActivityStopped(activity: Activity) {
    }

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {
    }

    override fun onActivityDestroyed(activity: Activity) {
        if (currentActivity.get() === activity) {
            currentActivity = WeakReference(null)
        }
        if (resumedActivity.get() === activity) {
            resumedActivity = WeakReference(null)
        }
    }
}
