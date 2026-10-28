package com.hermes.xmcam.oemderived.compat;

import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureRequest;
import android.os.Build;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Vendor capture-request key list for CameraCapabilities, built with public Camera2 API only.
 * See {@link VendorKeyLogic} for the why. No hidden API, no reflection.
 */
public final class VendorKeyCompat {

    private static final String TAG = "XMCAM_VENDORKEY";
    private static final ConcurrentHashMap<String, Boolean> KNOWN = new ConcurrentHashMap<String, Boolean>();
    private static volatile boolean logged;

    private VendorKeyCompat() {
    }

    public static ArrayList<?> captureRequestVendorKeys(final CameraCharacteristics characteristics) {
        try {
            ArrayList<String> publicNames = new ArrayList<String>();
            try {
                List<CaptureRequest.Key<?>> keys = characteristics.getAvailableCaptureRequestKeys();
                if (keys != null) {
                    for (CaptureRequest.Key<?> k : keys) {
                        publicNames.add(k.getName());
                    }
                }
            } catch (RuntimeException e) {
                Log.w(TAG, "available request keys unreadable: " + e);
            }
            List<String> names = VendorKeyLogic.collect(publicNames, VendorTagNames.ALL, new VendorKeyLogic.TagProbe() {
                @Override
                public boolean isKnown(String name) {
                    Boolean cached = KNOWN.get(name);
                    if (cached != null) {
                        return cached.booleanValue();
                    }
                    boolean known;
                    try {
                        characteristics.get(new CameraCharacteristics.Key<Object>(name, Object.class));
                        known = true;
                    } catch (IllegalArgumentException e) {
                        known = false; // tag name not in the vendor-tag descriptor
                    } catch (RuntimeException e) {
                        known = true; // name resolved; only the value type could not be read
                    }
                    KNOWN.put(name, Boolean.valueOf(known));
                    return known;
                }
            });
            ArrayList<CaptureRequest.Key<?>> out = new ArrayList<CaptureRequest.Key<?>>(names.size());
            for (String n : names) {
                out.add(new CaptureRequest.Key<Object>(n, Object.class));
            }
            if (!logged) {
                logged = true;
                Log.i(TAG, "vendor capture keys: public=" + publicNames.size() + " resolved=" + out.size()
                        + " of " + VendorTagNames.ALL.length + " candidate names");
            }
            return out;
        } catch (Throwable t) {
            Log.w(TAG, "vendor capture keys unavailable: " + t);
            return null;
        }
    }

    /** Android 9+ frameworks set up the vendor-tag descriptor themselves when the camera service connects. */
    public static boolean frameworkHasVendorTags() {
        return Build.VERSION.SDK_INT >= 28;
    }

    /** Value for CameraCapabilities.mIsIncomplete given the vendor capture-key set emptiness. */
    public static boolean incompleteFlag(boolean vendorKeysEmpty) {
        return CameraRoleFallbackLogic.incompleteFlag(vendorKeysEmpty, frameworkHasVendorTags());
    }
}
