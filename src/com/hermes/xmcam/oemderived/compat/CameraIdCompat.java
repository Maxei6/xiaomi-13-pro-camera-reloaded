package com.hermes.xmcam.oemderived.compat;

import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraManager;
import android.util.Log;

import com.hermes.xmcam.oemderived.portlog.PortLog;

/**
 * stage21 v3: camera id list for the OEM role map and capability tables.
 *
 * Replaces the public-only CameraManager.getCameraIdList() in Camera2CompatAdapterRole.init,
 * Camera2CompatAdapterCommon.init (smali patch_stage21_ids.py) and CameraRoleFallback.
 * Public API only: each hidden candidate id is probed with getCameraCharacteristics(id). An id is
 * added only when that call returns; the exception path is logged and skipped. No reflection,
 * no hidden API, no vendor service call.
 *
 * Candidates 2..8 come from the phone's HAL dump (ids 2..8 expose vendor role tags). Ids 9 and 10
 * are EXTERNAL reprocessing ids and are deliberately not probed.
 */
public final class CameraIdCompat {
    private static final String TAG = "XMCAM_CAMIDS";
    static final int[] HIDDEN_CANDIDATES = {2, 3, 4, 5, 6, 7, 8};

    private CameraIdCompat() {
    }

    public static String[] listIds(CameraManager cm) throws CameraAccessException {
        String[] pub = cm.getCameraIdList();
        boolean[] readable = new boolean[HIDDEN_CANDIDATES.length];
        StringBuilder skipped = new StringBuilder();
        for (int i = 0; i < HIDDEN_CANDIDATES.length; i++) {
            String id = Integer.toString(HIDDEN_CANDIDATES[i]);
            boolean isPublic = false;
            for (String p : pub) {
                if (id.equals(p)) {
                    isPublic = true;
                    break;
                }
            }
            if (isPublic) {
                continue;
            }
            try {
                cm.getCameraCharacteristics(id);
                readable[i] = true;
            } catch (CameraAccessException | RuntimeException e) {
                skipped.append(id).append(':').append(e.getClass().getSimpleName()).append(' ');
            }
        }
        String[] out = CameraIdListLogic.merge(pub, HIDDEN_CANDIDATES, readable);
        Log.i(TAG, "camera ids public=" + java.util.Arrays.toString(pub)
                + " final=" + java.util.Arrays.toString(out) + " skipped=" + skipped);
        PortLog.event("CAMERA_IDS public=" + java.util.Arrays.toString(pub)
                + " final=" + java.util.Arrays.toString(out) + " skipped=[" + skipped.toString().trim() + "]");
        return out;
    }
}
