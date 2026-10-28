package com.hermes.xmcam.oemderived.compat;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure logic (no Android imports) for the stage21 v3 camera id list.
 *
 * CameraManager.getCameraIdList() on the Xiaomi 13 Pro returns only [0, 1]. The OEM role map is built
 * from that list, so the hidden physical/logical ids (2..8 on the phone, see phone-evidence/hal-vendor-roles-and-tags.txt)
 * never contribute their vendor role tags. This rule adds a candidate id only when CameraManager.getCameraCharacteristics(id)
 * returns without an exception. Public ids keep their order; candidates are appended in ascending order, no duplicates.
 */
public final class CameraIdListLogic {
    private CameraIdListLogic() {
    }

    /**
     * @param publicIds  ids from CameraManager.getCameraIdList(), in framework order
     * @param candidates numeric candidate ids that were probed
     * @param readable   readable[i] is true when getCameraCharacteristics(candidates[i]) did not throw
     * @return public ids followed by the readable candidates that are not already in the public list
     */
    public static String[] merge(String[] publicIds, int[] candidates, boolean[] readable) {
        List<String> out = new ArrayList<String>();
        for (String p : publicIds) {
            if (!out.contains(p)) {
                out.add(p);
            }
        }
        for (int i = 0; i < candidates.length; i++) {
            if (!readable[i]) {
                continue;
            }
            String id = Integer.toString(candidates[i]);
            if (!out.contains(id)) {
                out.add(id);
            }
        }
        return out.toArray(new String[out.size()]);
    }
}
