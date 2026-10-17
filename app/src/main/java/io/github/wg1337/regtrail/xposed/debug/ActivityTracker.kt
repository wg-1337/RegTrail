package io.github.wg1337.regtrail.xposed.debug

import android.app.Activity
import android.app.Application
import android.os.Bundle
import io.github.wg1337.regtrail.xposed.RegTrailLog
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import java.lang.ref.WeakReference

/**
 * Tracks the target app's live Activities.
 *
 * The debugger needs to answer "what is on screen right now?" and "dump the view
 * tree", both of which require an `Activity` instance. Android has no public API
 * for that, so the module registers an [Application.ActivityLifecycleCallbacks]
 * as soon as it gets the Application, and keeps weak references so tracking can
 * never leak an Activity.
 *
 * Weak references matter here: this module lives inside a long-running
 * third-party process, and a strong reference to a destroyed Activity would be
 * a memory leak in someone else's app.
 */
internal object ActivityTracker {

    private val activities = CopyOnWriteArrayList<WeakReference<Activity>>()

    private val seq = AtomicLong(0)

    @Volatile
    private var registered = false

    fun register(app: Application) {
        if (registered) return
        registered = true
        runCatching {
            app.registerActivityLifecycleCallbacks(object :
                Application.ActivityLifecycleCallbacks {

                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                    activities.add(WeakReference(activity))
                    RegTrailLog.d("activity created: ${activity.javaClass.name}")
                }

                override fun onActivityStarted(activity: Activity) = Unit

                override fun onActivityResumed(activity: Activity) {
                    // Move to the end so "last" means "most recently resumed".
                    activities.removeAll { it.get() === activity || it.get() == null }
                    activities.add(WeakReference(activity))
                    RegTrailLog.d("activity resumed: ${activity.javaClass.name}")
                }

                override fun onActivityPaused(activity: Activity) = Unit

                override fun onActivityStopped(activity: Activity) = Unit

                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

                override fun onActivityDestroyed(activity: Activity) {
                    activities.removeAll { it.get() === activity || it.get() == null }
                    RegTrailLog.d("activity destroyed: ${activity.javaClass.name}")
                }
            })
        }.onFailure { RegTrailLog.w("failed to register activity tracker", it) }
    }

    /** Live Activities, most recently resumed last. Dead references are pruned. */
    fun current(): List<Activity> {
        activities.removeAll { it.get() == null }
        return activities.mapNotNull { it.get() }
    }

    /** The Activity a user would consider "on screen". */
    fun top(): Activity? = current().lastOrNull()

    /**
     * Hands out a stable handle for an Activity so the settings app can refer to
     * it over the bridge without serializing the object itself.
     */
    fun handleOf(activity: Activity): String {
        val handle = "act-${seq.incrementAndGet()}"
        InstanceRegistry.put(handle, activity)
        return handle
    }
}
