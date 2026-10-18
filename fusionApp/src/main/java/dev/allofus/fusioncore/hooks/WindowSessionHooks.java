package dev.allofus.fusioncore.hooks;

import android.content.Context;
import android.util.Log;
import android.view.WindowManager;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;

/**
 * Normalizes the package name of toast windows added by game code.
 *
 * Game code creates toasts with a context whose package is the game's (e.g.
 * com.PigeonGames.Phigros). Toast windows have no window token, so WindowManagerService
 * rejects them unless the package in the window attributes belongs to the calling UID
 * (the launcher). On Android 16 that rejection is a SecurityException thrown on the main
 * thread -> "Package <game> not in UID <launcher>" -> the whole hosted game process dies.
 *
 * The window session proxy rewrites the package of TYPE_TOAST windows to the host package
 * right before the add reaches WindowManagerService. Other window types are left untouched
 * because activity windows carry a token and are not subject to this check.
 */
public final class WindowSessionHooks {
    private WindowSessionHooks() {}

    private static final String TAG = "WindowSessionHooks";

    public static void install(Context fusionContext) throws ReflectiveOperationException {
        Class<?> globalClass = Class.forName("android.view.WindowManagerGlobal");
        Field sessionField = globalClass.getDeclaredField("sWindowSession");
        sessionField.setAccessible(true);
        Object original = sessionField.get(null);
        if (original == null) {
            Log.w(TAG, "Window session not created yet; toast package rewrite not installed");
            return;
        }
        if (Proxy.isProxyClass(original.getClass())) {
            Log.d(TAG, "Window session already hooked");
            return;
        }

        String hostPackage = fusionContext.getPackageName();
        Class<?> sessionInterface = Class.forName("android.view.IWindowSession");
        Object proxy = Proxy.newProxyInstance(WindowSessionHooks.class.getClassLoader(),
                new Class<?>[] {sessionInterface}, (object, method, args) -> {
                    if (args != null) {
                        for (int i = 0; i < args.length; i++) {
                            if (args[i] instanceof WindowManager.LayoutParams params) {
                                if (params.type == WindowManager.LayoutParams.TYPE_TOAST
                                        && !hostPackage.equals(params.packageName)) {
                                    Log.d(TAG, method.getName() + " toast window package: "
                                            + params.packageName + " -> " + hostPackage);
                                    params.packageName = hostPackage;
                                } else if (!hostPackage.equals(params.packageName)) {
                                    Log.d(TAG, method.getName() + " window type=" + params.type
                                            + " package=" + params.packageName);
                                }
                            }
                        }
                    }
                    try {
                        return method.invoke(original, args);
                    } catch (InvocationTargetException error) {
                        Log.e(TAG, "Window session " + method.getName() + " failed", error.getCause());
                        throw error.getCause();
                    }
                });
        sessionField.set(null, proxy);
        Log.i(TAG, "Window session hooked (host package " + hostPackage + ")");
    }
}
