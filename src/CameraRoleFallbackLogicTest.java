package com.hermes.xmcam.oemderived.compat;

import java.util.HashMap;
import java.util.Map;

/**
 * Host test for the stage21 LENS_FACING role fallback rule (no Android runtime).
 * Run: java -ea -cp <classes> com.hermes.xmcam.oemderived.compat.CameraRoleFallbackLogicTest
 * Supersedes stage6 CameraRoleFallbackLogicTest, which asserted the old "skip if any standard
 * role exists" rule that hid the phone's missing back role (phone: role 1 present, role 0 absent).
 */
public final class CameraRoleFallbackLogicTest {

    private static final class MapFake implements CameraRoleFallbackLogic.RoleMap {
        final Map<Integer, Integer> m = new HashMap<Integer, Integer>();

        @Override
        public boolean has(int role) {
            return m.containsKey(role);
        }

        @Override
        public void put(int role, int cameraId) {
            m.put(role, cameraId);
        }

        @Override
        public void delete(int role) {
            m.remove(role);
        }
    }

    private static int passed;
    private static int total;

    private static void check(String name, boolean ok, String detail) {
        total++;
        if (ok) {
            passed++;
        }
        System.out.println((ok ? "PASS " : "FAIL ") + name + (detail.isEmpty() ? "" : "  [" + detail + "]"));
    }

    private static Map<Integer, Integer> map(int... kv) {
        Map<Integer, Integer> r = new HashMap<Integer, Integer>();
        for (int i = 0; i < kv.length; i += 2) {
            r.put(kv[i], kv[i + 1]);
        }
        return r;
    }

    public static void main(String[] args) {
        // 1. emulator/AOSP: no vendor roles, back id 0, front id 1 -> both standard roles filled
        MapFake aosp = new MapFake();
        boolean c1 = CameraRoleFallbackLogic.apply(aosp, new int[]{0, 1}, new int[]{1, 0});
        check("aosp: back 0 -> role 0, front 1 -> role 1, no placeholder",
                c1 && aosp.m.equals(map(0, 0, 1, 1)), aosp.m.toString());

        // 2. phone (Xiaomi 13 Pro): public ids [0,1]; id0 role 100 (cameraIds), id1 role 1; no role 0
        MapFake phone = new MapFake();
        phone.put(100, 0);
        phone.put(1, 1);
        boolean c2 = CameraRoleFallbackLogic.apply(phone, new int[]{0, 1}, new int[]{1, 0});
        check("phone: missing back role 0 filled from LENS_FACING back (id 0), vendor roles kept",
                c2 && phone.m.equals(map(100, 0, 1, 1, 0, 0)), phone.m.toString());

        // 3. MIUI full map: both standard roles present -> untouched
        MapFake miui = new MapFake();
        miui.put(0, 0);
        miui.put(1, 1);
        miui.put(-1, 7);
        boolean c3 = CameraRoleFallbackLogic.apply(miui, new int[]{0, 1, 7}, new int[]{1, 0, 1});
        check("miui both roles present: no change, placeholder kept",
                !c3 && miui.m.equals(map(0, 0, 1, 1, -1, 7)), miui.m.toString());

        // 4. uninitialised map (only placeholder -1) -> standard roles filled, placeholder removed
        MapFake uninit = new MapFake();
        uninit.put(-1, 5);
        boolean c4 = CameraRoleFallbackLogic.apply(uninit, new int[]{0, 1}, new int[]{1, 0});
        check("placeholder only: roles filled, -1 removed", c4 && uninit.m.equals(map(0, 0, 1, 1)),
                uninit.m.toString());

        // 5. front only in the public list -> front mapped, no back invented
        MapFake front = new MapFake();
        boolean c5 = CameraRoleFallbackLogic.apply(front, new int[]{3}, new int[]{0});
        check("front only: role 1 -> id 3, no back invented", c5 && front.m.equals(map(1, 3)), front.m.toString());

        // 6. external only -> nothing standard to map, placeholder kept
        MapFake none = new MapFake();
        none.put(-1, 5);
        boolean c6 = CameraRoleFallbackLogic.apply(none, new int[]{5}, new int[]{2});
        check("external only: no change, placeholder kept", !c6 && none.m.equals(map(-1, 5)), none.m.toString());

        // 7. back role present (vendor), front absent, a front camera is public -> front added only
        MapFake backOnly = new MapFake();
        backOnly.put(0, 2);
        boolean c7 = CameraRoleFallbackLogic.apply(backOnly, new int[]{1, 2}, new int[]{0, 1});
        check("back role present, front missing: front 1 added, back role untouched",
                c7 && backOnly.m.equals(map(0, 2, 1, 1)), backOnly.m.toString());

        // 8. resolveBogusBackId keeps stock behaviour
        boolean r = CameraRoleFallbackLogic.resolveBogusBackId(0, 1) == 1
                && CameraRoleFallbackLogic.resolveBogusBackId(0, -1) == 0
                && CameraRoleFallbackLogic.resolveBogusBackId(1, 1) == 1;
        check("resolveBogusBackId: mapped back used, unmapped keeps bogus 0", r, "");

        System.out.println("RESULT: " + passed + "/" + total + " passed");
        if (passed != total) {
            System.exit(1);
        }
    }
}
