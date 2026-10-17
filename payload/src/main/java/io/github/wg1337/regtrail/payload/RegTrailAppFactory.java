package io.github.wg1337.regtrail.payload;

import android.app.AppComponentFactory;
import android.app.Application;
import android.util.Log;

/**
 * The injection entry point: a custom {@code android:appComponentFactory}.
 *
 * <p>## Why this and not a replacement {@code Application}
 *
 * <p>The patcher rewrites the target APK's binary manifest to point
 * {@code <application android:appComponentFactory>} at this class. The platform
 * instantiates the factory before the {@code Application} exists and asks it to
 * create that {@code Application} ({@code ActivityThread#newApplication}).
 *
 * <p>Two other techniques were considered and rejected:
 *
 * <ul>
 *   <li>Replacing {@code <application android:name>} with a stub that reflects
 *       into {@code ActivityThread}/hidden API to build the original
 *       {@code Application} — exactly the hidden-API surface that is blocked on
 *       modern Android.</li>
 *   <li>Xposed-style hooking — this payload must run on a device with no root and
 *       no LSPosed, so there is no hooking framework to hook with.</li>
 * </ul>
 *
 * <p>Delegating to {@code super.instantiateApplication} is the whole trick: the
 * original {@code Application} is created by the platform exactly as usual, with
 * no reflection into its internals. We only get to observe it afterwards.
 *
 * <p>## API level
 *
 * <p>{@code AppComponentFactory} is API 28+, which is why the patcher also raises
 * the target APK's {@code minSdkVersion} to 28. Raising minSdk (not targetSdk)
 * cannot change the app's runtime behaviour contract; it only declares the floor
 * this payload needs. The patcher additionally refuses targets whose
 * {@code targetSdkVersion} is below the current install floor.
 */
public final class RegTrailAppFactory extends AppComponentFactory {

    /** Log tag for everything the payload does. */
    public static final String TAG = OverlayHost.TAG;

    /**
     * Guards against double installation. Each process gets its own class loader
     * and its own {@code Application}, but {@code instantiateApplication} can in
     * principle be called once per process only — the flag makes the intent
     * explicit and keeps a re-entrant call harmless.
     */
    private static volatile boolean installed;

    @Override
    public Application instantiateApplication(ClassLoader cl, String className)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        // The platform's own implementation: cl.loadClass(className).newInstance().
        // Note this also covers apps that declare no android:name at all, for
        // which the framework passes "android.app.Application".
        Application app = super.instantiateApplication(cl, className);

        if (!installed) {
            installed = true;
            try {
                OverlayHost.install(app);
            } catch (Throwable t) {
                // A payload that crashes the host app is worse than no payload at
                // all: the user cannot even uninstall cleanly if the app dies on
                // launch. Every failure stays inside the payload.
                Log.e(TAG, "overlay install failed for Application " + className, t);
            }
        }
        return app;
    }
}
