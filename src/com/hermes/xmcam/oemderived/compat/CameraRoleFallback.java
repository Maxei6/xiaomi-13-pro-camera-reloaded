package com.hermes.xmcam.oemderived.compat;

import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.util.Log;
import android.util.SparseIntArray;

import com.hermes.xmcam.oemderived.portlog.PortLog;

/**
 * Android adapter for {@link CameraRoleFallbackLogic}. Called from Camera2CompatAdapterRole.init()
 * right after the role map is built, through the smali patch in patch_stage6.py.
 *
 * stage21: logs the role map before and after (PortLog), so the user log shows which role is missing.
 * Errors are logged and swallowed so that camera init never fails because of this fallback.
 */
public final class CameraRoleFallback {
    private static final String TAG = "XMCAM_ROLEFALLBACK";

    private CameraRoleFallback() {
    }

    public static void apply(CameraManager cameraManager, SparseIntArray roleMap) {
        PortLog.roleMap("before-fallback", roleMap);
        try {
            String[] idList = CameraIdCompat.listIds(cameraManager);
            int[] ids = new int[idList.length];
            int[] facing = new int[idList.length];
            int n = 0;
            for (String id : idList) {
                int numeric;
                try {
                    numeric = Integer.parseInt(id);
                } catch (NumberFormatException e) {
                    continue;
                }
                Integer lf = cameraManager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING);
                ids[n] = numeric;
                facing[n] = lf == null ? -1 : lf;
                n++;
            }
            int[] idsTrim = new int[n];
            int[] facingTrim = new int[n];
            System.arraycopy(ids, 0, idsTrim, 0, n);
            System.arraycopy(facing, 0, facingTrim, 0, n);
            boolean changed = CameraRoleFallbackLogic.apply(new CameraRoleFallbackLogic.RoleMap() {
                @Override
                public boolean has(int role) {
                    return roleMap.indexOfKey(role) >= 0;
                }

                @Override
                public void put(int role, int cameraId) {
                    roleMap.put(role, cameraId);
                }

                @Override
                public void delete(int role) {
                    roleMap.delete(role);
                }
            }, idsTrim, facingTrim);
            Log.i(TAG, "LENS_FACING role fallback applied=" + changed
                    + " back=" + roleMap.get(CameraRoleFallbackLogic.ROLE_BACK, -1)
                    + " front=" + roleMap.get(CameraRoleFallbackLogic.ROLE_FRONT, -1));
            PortLog.event("ROLE_FALLBACK applied=" + changed + " publicIds=" + java.util.Arrays.toString(idsTrim)
                    + " lensFacing=" + java.util.Arrays.toString(facingTrim));
            PortLog.roleMap("after-fallback", roleMap);
        } catch (CameraAccessException | RuntimeException e) {
            Log.e(TAG, "role fallback skipped: " + e);
            PortLog.error("ROLE_FALLBACK skipped", e);
        }
    }
}
