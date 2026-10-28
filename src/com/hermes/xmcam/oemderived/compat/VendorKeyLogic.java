package com.hermes.xmcam.oemderived.compat;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/**
 * Pure logic (no Android imports) for building the vendor-key name list that CameraCapabilities uses
 * for its "is this vendor tag defined" checks.
 *
 * Root cause (v7 phone log, HyperOS 3 / Android 16): the OEM read the list through the hidden
 * CameraCharacteristics.getNativeCopy() + CameraMetadataNative.getAllVendorKeys(), which Android 16
 * denies. The list stayed empty, so every vendor tag looked undefined (for example
 * xiaomi.snapshot.imageName), the picture name was never generated and no photo was saved.
 *
 * getAllVendorKeys returns every tag of the global vendor-tag descriptor. The public equivalent is:
 *  1. the vendor names the HAL lists in the public available-request-keys, plus
 *  2. every vendor tag name the OEM code itself can ask for, kept when the framework resolves it
 *     (CameraCharacteristics.get(key) throws IllegalArgumentException only for an unknown tag name).
 * Names in the "android." namespace are standard keys and are never part of the vendor list.
 */
public final class VendorKeyLogic {

    /** Answers whether the framework resolves this tag name through its vendor-tag descriptor. */
    public interface TagProbe {
        boolean isKnown(String name);
    }

    private VendorKeyLogic() {
    }

    public static boolean isVendorName(String name) {
        return name != null && name.indexOf('.') > 0 && !name.startsWith("android.") && !name.endsWith(".");
    }

    /**
     * v11: vendor tags whose value type is the hidden android.hardware.camera2.params.StreamConfiguration[].
     * Android 16 blocks its getters (getFormat/getSize/isOutput are greylist-max-o), so a "defined" answer made
     * the OEM code call them and die with NoSuchMethodError (phone log, mode switch). Reporting them as not defined
     * sends the OEM code down its existing "tag missing" branch, the same one it used before v9.
     */
    public static boolean isHiddenTypedName(String name) {
        return name != null && name.toLowerCase(java.util.Locale.ROOT).contains("streamconfigurations");
    }

    /** Public names first (vendor ones only), then resolvable candidates; order kept, no duplicates. */
    public static List<String> collect(List<String> publicNames, String[] candidates, TagProbe probe) {
        ArrayList<String> out = new ArrayList<String>();
        HashSet<String> seen = new HashSet<String>();
        if (publicNames != null) {
            for (String n : publicNames) {
                if (isVendorName(n) && !isHiddenTypedName(n) && seen.add(n)) {
                    out.add(n);
                }
            }
        }
        if (candidates != null && probe != null) {
            for (String n : candidates) {
                if (!isVendorName(n) || isHiddenTypedName(n) || seen.contains(n)) {
                    continue;
                }
                boolean known;
                try {
                    known = probe.isKnown(n);
                } catch (RuntimeException e) {
                    known = false;
                }
                if (known) {
                    seen.add(n);
                    out.add(n);
                }
            }
        }
        return out;
    }
}
