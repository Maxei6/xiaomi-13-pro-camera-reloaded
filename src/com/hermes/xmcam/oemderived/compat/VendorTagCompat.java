package com.hermes.xmcam.oemderived.compat;

import android.util.Log;

/**
 * The OEM called the hidden CameraMetadataNative.setupGlobalVendorTagDescriptor() at start-up.
 * Android 9+ sets the global vendor-tag descriptor itself when the camera service connects (the first
 * CameraManager use), so nothing is needed and no hidden API is touched. Kept as a no-op with the same
 * signature so the patched call site in Camera2CompatAdapterRole stays valid.
 */
public final class VendorTagCompat {

    private static final String TAG = "XMCAM_VENDORTAG";
    private static volatile boolean logged;

    private VendorTagCompat() {
    }

    public static void setupGlobalVendorTagDescriptorIfPresent() {
        if (!logged) {
            logged = true;
            Log.i(TAG, "vendor-tag descriptor is framework-managed; no hidden call");
        }
    }
}
