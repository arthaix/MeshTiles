package ru.arthaix.meshtiles.server;

import java.util.ArrayList;
import java.util.List;

/**
 * Subtracts already occupied boxes from a box that is about to be placed, so that only the free part of a tile is
 * written. Boxes are int arrays {minX, minY, minZ, maxX, maxY, maxZ} with exclusive maxima, all in the same grid.
 */
public final class BoxCutter {

    /** Guards against pathological fragmentation: a box that would split into more pieces than this is dropped. */
    public static final int MAX_PIECES = 256;

    private BoxCutter() {}

    public static boolean intersects(int[] a, int[] b) {
        return a[0] < b[3] && a[3] > b[0] && a[1] < b[4] && a[4] > b[1] && a[2] < b[5] && a[5] > b[2];
    }

    /**
     * @return the parts of {@code box} that lie outside every box in {@code occupied}, or {@code null} when the result
     *         would be too fragmented. The list is empty when the box is completely covered.
     */
    public static List<int[]> subtract(int[] box, List<int[]> occupied) {
        List<int[]> rem = new ArrayList<>(4);
        rem.add(box);
        for (int[] o : occupied) {
            if (!intersects(box, o)) continue;
            List<int[]> next = new ArrayList<>(rem.size() + 4);
            for (int[] r : rem) {
                if (!intersects(r, o)) {
                    next.add(r);
                    continue;
                }
                // slabs left/right of o along X, then front/back along Y inside the X overlap, then along Z
                if (r[0] < o[0]) next.add(new int[] { r[0], r[1], r[2], o[0], r[4], r[5] });
                if (r[3] > o[3]) next.add(new int[] { o[3], r[1], r[2], r[3], r[4], r[5] });
                int x0 = Math.max(r[0], o[0]), x1 = Math.min(r[3], o[3]);
                if (r[1] < o[1]) next.add(new int[] { x0, r[1], r[2], x1, o[1], r[5] });
                if (r[4] > o[4]) next.add(new int[] { x0, o[4], r[2], x1, r[4], r[5] });
                int y0 = Math.max(r[1], o[1]), y1 = Math.min(r[4], o[4]);
                if (r[2] < o[2]) next.add(new int[] { x0, y0, r[2], x1, y1, o[2] });
                if (r[5] > o[5]) next.add(new int[] { x0, y0, o[5], x1, y1, r[5] });
            }
            if (next.size() > MAX_PIECES) return null;
            rem = next;
        }
        return rem;
    }
}
