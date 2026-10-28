package com.hermes.xmcam.oemderived.compat;

/**
 * Host test for CameraIdListLogic (stage21 v3). No Android runtime.
 * Run: java -ea -cp <classes> com.hermes.xmcam.oemderived.compat.CameraIdListLogicTest
 */
public final class CameraIdListLogicTest {
    private static int passed;
    private static int total;

    private static void check(String name, String[] got, String... want) {
        total++;
        boolean ok = java.util.Arrays.equals(got, want);
        if (ok) {
            passed++;
        }
        System.out.println((ok ? "PASS " : "FAIL ") + name + " got=" + java.util.Arrays.toString(got)
                + " want=" + java.util.Arrays.toString(want));
    }

    public static void main(String[] args) {
        int[] cand = {2, 3, 4, 5, 6, 7, 8};
        // phone case: 2,3,5,6,7 readable; 4 and 8 throw
        check("phone-like merge",
                CameraIdListLogic.merge(new String[] {"0", "1"}, cand,
                        new boolean[] {true, true, false, true, true, true, false}),
                "0", "1", "2", "3", "5", "6", "7");
        // candidate already public is not duplicated
        check("no duplicate of public id",
                CameraIdListLogic.merge(new String[] {"0", "1", "2"}, new int[] {2},
                        new boolean[] {true}),
                "0", "1", "2");
        // nothing readable: public list unchanged
        check("nothing readable keeps public list",
                CameraIdListLogic.merge(new String[] {"0", "1"}, cand,
                        new boolean[] {false, false, false, false, false, false, false}),
                "0", "1");
        System.out.println("RESULT: " + passed + "/" + total + " passed");
        if (passed != total) {
            System.exit(1);
        }
    }
}
