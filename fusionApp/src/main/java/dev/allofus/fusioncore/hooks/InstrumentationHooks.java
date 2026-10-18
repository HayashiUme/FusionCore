package dev.allofus.fusioncore.hooks;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.os.Bundle;
import android.util.Log;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import dev.allofus.fusioncore.BuildConfig;
import dev.allofus.fusioncore.StubActivity;

/** Intent wrapping and orientation support for dynamically loaded activities. */
public class InstrumentationHooks {

    private static final String TAG = "InstrumentationHooks";

    public static final String EXTRA_IS_DYNAMIC_ACTIVITY = "fusioncore.is_dynamic_activity";
    /** Legacy key holding the original intent as a Parcelable extra. Only read as a fallback. */
    public static final String EXTRA_ORIGINAL_INTENT = "fusioncore.original_intent";
    /** Preferred key: opaque token identifying an in-process pending original intent. */
    public static final String EXTRA_ORIGINAL_INTENT_TOKEN = "fusioncore.original_intent_token";
    public static final String EXTRA_TARGET_ORIENTATION = "fusioncore.target_orientation";
    public static final String EXTRA_FUSION_CONFIG = "fusioncore.config";

    private static final int MAX_PENDING_INTENTS = 32;

    /**
     * Original intents handed to stub activities, kept in this process instead of being
     * parceled into the stub intent.
     *
     * Putting the original intent into the stub intent's extras forces the whole intent
     * (including extras holding game-dex classes, e.g. TapTap's LoginRequest) through the
     * system binder. When those extras are lazily unparceled on the way back, the game
     * classes are resolved with this app's classloader, which cannot see them ->
     * BadParcelableException: ClassNotFoundException when unmarshalling. The in-process
     * handoff avoids unmarshalling game classes entirely.
     */
    private static final Map<String, Intent> PENDING_ORIGINAL_INTENTS =
            Collections.synchronizedMap(new LinkedHashMap<String, Intent>() {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Intent> eldest) {
                    return size() > MAX_PENDING_INTENTS;
                }
            });

    static void applyTargetOrientation(Activity activity) {
        try {
            Intent intent = activity.getIntent();
            if (intent == null) {
                return;
            }
            int orientation = readTargetOrientation(intent);
            if (orientation == ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) {
                return;
            }
            activity.setRequestedOrientation(orientation);
            Log.i(TAG, "Applied target orientation " + orientation
                    + " to " + activity.getClass().getName());
        } catch (Exception e) {
            Log.e(TAG, "Failed to apply target orientation", e);
        }
    }

    private static int readTargetOrientation(Intent intent) {
        int orientation = intent.getIntExtra(EXTRA_TARGET_ORIENTATION,
                ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        if (orientation != ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) {
            return orientation;
        }
        Intent original = resolveOriginalIntent(intent);
        if (original != null) {
            return original.getIntExtra(EXTRA_TARGET_ORIENTATION,
                    ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        }
        return ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
    }

    /** Stores the original intent in-process and returns the token identifying it. */
    public static String stashOriginalIntent(Intent intent) {
        String token = UUID.randomUUID().toString();
        PENDING_ORIGINAL_INTENTS.put(token, intent);
        return token;
    }

    static Intent resolveOriginalIntent(Intent currentIntent) {
        try {
            String token = currentIntent.getStringExtra(EXTRA_ORIGINAL_INTENT_TOKEN);
            if (token != null) {
                Intent originalIntent = PENDING_ORIGINAL_INTENTS.get(token);
                if (originalIntent != null && originalIntent.getComponent() != null) {
                    Log.d(TAG, "Resolved original intent for " + originalIntent.getComponent().getClassName());
                    return originalIntent;
                }
                Log.w(TAG, "Pending original intent for token not found; falling back to parcel extra");
            }

            // Fallback for intents wrapped before the token scheme existed.
            currentIntent.setExtrasClassLoader(InstrumentationHooks.class.getClassLoader());
            Intent originalIntent = currentIntent.getParcelableExtra(EXTRA_ORIGINAL_INTENT);

            if (originalIntent != null && originalIntent.getComponent() != null) {
                Log.d(TAG, "Resolved original intent (legacy) for " + originalIntent.getComponent().getClassName());
                return originalIntent;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error resolving original intent", e);
        }
        return null;
    }

    static Intent getInjectedIntent(Intent intent) {
        String token = stashOriginalIntent(intent);

        Intent newIntent = new Intent(intent);
        // Drop the copied extras: they may contain game-dex Parcelables that must never be
        // marshalled through the system and unparceled with this app's classloader. The
        // original intent travels in-process via the token instead.
        newIntent.replaceExtras((Bundle) null);
        newIntent.putExtra(EXTRA_IS_DYNAMIC_ACTIVITY, true);
        newIntent.putExtra(EXTRA_ORIGINAL_INTENT_TOKEN, token);
        newIntent.setComponent(new ComponentName(BuildConfig.APPLICATION_ID, StubActivity.class.getName()));
        return newIntent;
    }

    static boolean isDynamicIntent(Intent intent) {
        if (intent == null) return false;

        return intent.getBooleanExtra(EXTRA_IS_DYNAMIC_ACTIVITY, false);
    }
}
