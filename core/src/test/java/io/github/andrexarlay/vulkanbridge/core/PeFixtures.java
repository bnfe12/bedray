package io.github.andrexarlay.vulkanbridge.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Builds tiny synthetic PE files for tests. They are not loadable DLLs, only parseable headers. */
final class PeFixtures {
    private PeFixtures() {}

    static final int SIZE = 0x600;

    /** PE image with a version resource (when version != null) and an optional certificate table entry. */
    static byte[] pe(int machine, boolean pe32Plus, boolean dll, String version, boolean signed, int salt) {
        ByteBuffer b = ByteBuffer.allocate(SIZE).order(ByteOrder.LITTLE_ENDIAN);
        b.putShort(0, (short) 0x5A4D);
        b.putInt(0x3C, 0x80);
        int pe = 0x80;
        b.putInt(pe, 0x00004550);
        b.putShort(pe + 4, (short) machine);
        b.putShort(pe + 6, (short) 1);
        b.putShort(pe + 20, (short) 240);
        b.putShort(pe + 22, (short) (dll ? 0x2022 : 0x0022));
        int opt = pe + 24;
        b.putShort(opt, (short) (pe32Plus ? 0x20B : 0x10B));
        b.putInt(opt + 108, 16);
        if (version != null) {
            b.putInt(opt + 112 + 16, 0x1000);
            b.putInt(opt + 112 + 20, 0x200);
        }
        if (signed) {
            b.putInt(opt + 112 + 32, 0x5F0);
            b.putInt(opt + 112 + 36, 16);
        }
        int sec = opt + 240;
        b.put(sec, (byte) '.');
        b.put(sec + 1, (byte) 'r');
        b.putInt(sec + 8, 0x200);
        b.putInt(sec + 12, 0x1000);
        b.putInt(sec + 16, 0x200);
        b.putInt(sec + 20, 0x400);
        if (version != null) {
            int r = 0x400;
            b.putShort(r + 14, (short) 1);               // root: 1 id entry (type 16)
            b.putInt(r + 16, 16);
            b.putInt(r + 20, 0x80000000 | 0x18);
            b.putShort(r + 0x18 + 14, (short) 1);         // type dir: 1 name entry
            b.putInt(r + 0x18 + 16, 1);
            b.putInt(r + 0x18 + 20, 0x80000000 | 0x30);
            b.putShort(r + 0x30 + 14, (short) 1);         // name dir: 1 language entry
            b.putInt(r + 0x30 + 16, 0x409);
            b.putInt(r + 0x30 + 20, 0x48);                // leaf: data entry
            b.putInt(r + 0x48, 0x1000 + 0x58);            // data rva
            b.putInt(r + 0x48 + 4, 52);
            String[] p = version.split("\\.");
            int[] v = new int[4];
            for (int i = 0; i < 4; i++) {
                v[i] = Integer.parseInt(p[i]);
            }
            int d = r + 0x58;
            b.putInt(d, 0xFEEF04BD);
            b.putInt(d + 8, (v[0] << 16) | v[1]);
            b.putInt(d + 12, (v[2] << 16) | v[3]);
        }
        b.putInt(0x300, salt); // make hashes differ between fixtures
        return b.array();
    }

    static byte[] x64(String version) {
        return pe(PeInspector.MACHINE_AMD64, true, true, version, false, 1);
    }

    static byte[] zip(Object... nameAndBytes) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(bo)) {
            for (int i = 0; i < nameAndBytes.length; i += 2) {
                z.putNextEntry(new ZipEntry((String) nameAndBytes[i]));
                z.write((byte[]) nameAndBytes[i + 1]);
                z.closeEntry();
            }
        }
        return bo.toByteArray();
    }
}
