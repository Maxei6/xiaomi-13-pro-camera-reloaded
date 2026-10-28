package com.hermes.xmcam.oemderived.compat;

/**
 * Pure logic (no Android imports) for filling the camera role map from the standard
 * CameraCharacteristics.LENS_FACING when a standard role is missing from the vendor role map.
 *
 * stage21 change. Hypothesis, not yet proven on the phone: the stage6 rule in this class
 * ("return false when ROLE_BACK or ROLE_FRONT is already in the map", confirmed in the stage6 source)
 * skips the LENS_FACING fallback whenever ONE vendor role exists. If the phone map has a front
 * role but no back role, ROLE_BACK stays missing, Camera2DataContainer.getMainBackCameraId()
 * returns -1, and the open path fails. The exact phone role map is not confirmed in this build;
 * the PortLog "ROLE_MAP before/after" lines are there to confirm or reject it.
 *
 * stage21 rule: each standard role is filled independently, and only when it is missing and the
 * public list has a camera with the matching LENS_FACING. Roles that already exist are never
 * replaced. When both roles are present (the MIUI full-map case) nothing changes.
 * Standard camera API only. No MIUI service or vendor tag is faked.
 */
public final class CameraRoleFallbackLogic {
    /** Same value as Camera2DataContainer.BOGUS_CAMERA_ID_BACK. */
    public static final int ROLE_BACK = 0;
    /** Same value as Camera2DataContainer.BOGUS_CAMERA_ID_FRONT. */
    public static final int ROLE_FRONT = 1;
    /** Placeholder the role map stores when a camera has no vendor role id. */
    public static final int ROLE_UNKNOWN = -1;
    /** CameraCharacteristics.LENS_FACING_BACK / LENS_FACING_FRONT. */
    public static final int LENS_FACING_FRONT = 0;
    public static final int LENS_FACING_BACK = 1;

    private CameraRoleFallbackLogic() {
    }

    /** Minimal view of the role map, so the logic can run on the host. */
    public interface RoleMap {
        boolean has(int role);

        void put(int role, int cameraId);

        void delete(int role);
    }

    /**
     * @param ids         numeric camera ids reported by the framework (public list)
     * @param lensFacing  LENS_FACING value for each id at the same index
     * @return true when the map was changed
     */
    public static boolean apply(RoleMap map, int[] ids, int[] lensFacing) {
        int back = -1;
        int front = -1;
        for (int i = 0; i < ids.length; i++) {
            if (lensFacing[i] == LENS_FACING_BACK && back < 0) {
                back = ids[i];
            } else if (lensFacing[i] == LENS_FACING_FRONT && front < 0) {
                front = ids[i];
            }
        }
        boolean changed = false;
        if (back >= 0 && !map.has(ROLE_BACK)) {
            map.put(ROLE_BACK, back);
            changed = true;
        }
        if (front >= 0 && !map.has(ROLE_FRONT)) {
            map.put(ROLE_FRONT, front);
            changed = true;
        }
        if (changed) {
            map.delete(ROLE_UNKNOWN);
        }
        return changed;
    }

    /**
     * Bogus-to-actual id for the back camera on the non-dual path. Stock code returned the bogus
     * id 0 unchanged, which only works where the real back camera is id 0. When the role map knows
     * the real back camera, use it; otherwise keep the stock value.
     */
    public static int resolveBogusBackId(int bogusCameraId, int mappedBackId) {
        if (bogusCameraId == ROLE_BACK && mappedBackId >= 0) {
            return mappedBackId;
        }
        return bogusCameraId;
    }

    /**
     * "Incomplete" means MIUI vendor capture keys were expected but are missing. That only makes sense
     * when the framework has vendor-tag support at all. Without it, no vendor keys can exist, so the
     * capabilities are not reported as incomplete.
     */
    public static boolean incompleteFlag(boolean vendorKeysEmpty, boolean frameworkHasVendorTags) {
        return vendorKeysEmpty && frameworkHasVendorTags;
    }
}
