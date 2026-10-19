package io.github.andrexarlay.vulkanbridge.core;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Minimal, bounds-checked reader of the PE/COFF headers of a Windows DLL. Internal.
 * <p>
 * Reads only the headers, section table, security directory entry and the VS_VERSION_INFO resource. It does not
 * validate an Authenticode signature: {@link Info#hasEmbeddedSignature()} only says that a certificate table exists.
 * Format reference: Microsoft PE format specification (DOS header e_lfanew at 0x3C, COFF Machine at PE+4,
 * optional header magic 0x20B for PE32+, data directory 2 = resources, 4 = certificate table).
 */
public final class PeInspector {
    public static final int MACHINE_AMD64 = 0x8664;
    public static final int MACHINE_I386 = 0x014c;
    public static final int MACHINE_ARM64 = 0xAA64;

    /** Thrown when the file is not a well-formed PE image. */
    public static final class PeFormatException extends IOException {
        public PeFormatException(String message) {
            super(message);
        }
    }

    /**
     * @param machine               COFF machine id
     * @param dll                   IMAGE_FILE_DLL characteristic set
     * @param pe32Plus              optional header magic is 0x20B (64-bit)
     * @param hasEmbeddedSignature  certificate table directory is non-empty (NOT a validated signature)
     * @param version               file version {@code a.b.c.d} from VS_FIXEDFILEINFO, or null when the DLL has no version resource
     */
    public record Info(int machine, boolean dll, boolean pe32Plus, boolean hasEmbeddedSignature, String version) {
        public boolean isX64Dll() {
            return machine == MACHINE_AMD64 && dll && pe32Plus;
        }

        public String machineName() {
            return switch (machine) {
                case MACHINE_AMD64 -> "x64";
                case MACHINE_I386 -> "x86";
                case MACHINE_ARM64 -> "arm64";
                default -> String.format("machine 0x%04x", machine);
            };
        }
    }

    private PeInspector() {}

    public static Info inspect(Path file) throws IOException {
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
            return inspect(ch);
        }
    }

    public static Info inspect(FileChannel ch) throws IOException {
        long size = ch.size();
        ByteBuffer dos = read(ch, 0, 0x40, size);
        if (u16(dos, 0) != 0x5A4D) {
            throw new PeFormatException("not a PE file (no MZ header)");
        }
        long peOff = u32(dos, 0x3C);
        ByteBuffer coff = read(ch, peOff, 24, size);
        if (u32(coff, 0) != 0x00004550L) {
            throw new PeFormatException("not a PE file (no PE signature)");
        }
        int machine = u16(coff, 4);
        int sections = u16(coff, 6);
        int optSize = u16(coff, 20);
        int characteristics = u16(coff, 22);
        boolean dll = (characteristics & 0x2000) != 0;
        if (optSize < 2 || sections > 96) {
            throw new PeFormatException("malformed PE header (optional header size " + optSize + ", sections " + sections + ")");
        }
        long optOff = peOff + 24;
        ByteBuffer opt = read(ch, optOff, Math.min(optSize, 240), size);
        int magic = u16(opt, 0);
        boolean pe32Plus = magic == 0x20B;
        if (magic != 0x20B && magic != 0x10B) {
            throw new PeFormatException(String.format("bad optional header magic 0x%x", magic));
        }
        int ddBase = pe32Plus ? 112 : 96; // offset of the data directory array inside the optional header
        int numDirs = opt.limit() > ddBase + 4 ? (int) u32(opt, ddBase - 4) : 0;
        long resRva = 0, resSize = 0, certOff = 0, certSize = 0;
        if (numDirs > 2 && opt.limit() >= ddBase + 3 * 8) {
            resRva = u32(opt, ddBase + 2 * 8);
            resSize = u32(opt, ddBase + 2 * 8 + 4);
        }
        if (numDirs > 4 && opt.limit() >= ddBase + 5 * 8) {
            certOff = u32(opt, ddBase + 4 * 8); // for the certificate table this is a FILE offset
            certSize = u32(opt, ddBase + 4 * 8 + 4);
        }
        boolean signed = certOff != 0 && certSize != 0 && certOff + certSize <= size;

        long secOff = optOff + optSize;
        ByteBuffer sec = read(ch, secOff, sections * 40, size);
        String version = null;
        if (resRva != 0 && resSize != 0) {
            version = readVersion(ch, size, sec, sections, resRva);
        }
        return new Info(machine, dll, pe32Plus, signed, version);
    }

    // ---- resources: type 16 (RT_VERSION) -> first name -> first language -> data entry -> VS_FIXEDFILEINFO

    private static String readVersion(FileChannel ch, long size, ByteBuffer sec, int sections, long resRva) throws IOException {
        long resFile = rvaToFile(sec, sections, resRva);
        if (resFile < 0) {
            return null;
        }
        long typeDir = findSubdir(ch, size, resFile, resFile, 16);
        if (typeDir < 0) {
            return null;
        }
        long nameDir = firstEntry(ch, size, resFile, typeDir);
        if (nameDir < 0) {
            return null;
        }
        long dataEntryOff = firstEntryLeaf(ch, size, resFile, nameDir);
        if (dataEntryOff < 0) {
            return null;
        }
        ByteBuffer de = read(ch, dataEntryOff, 16, size);
        long dataRva = u32(de, 0);
        long dataLen = u32(de, 4);
        long dataFile = rvaToFile(sec, sections, dataRva);
        if (dataFile < 0 || dataLen < 52 || dataLen > 65536) {
            return null;
        }
        ByteBuffer blob = read(ch, dataFile, (int) dataLen, size);
        for (int i = 0; i + 52 <= blob.limit(); i += 4) {
            if (u32(blob, i) == 0xFEEF04BDL) {
                long ms = u32(blob, i + 8);
                long ls = u32(blob, i + 12);
                return (ms >>> 16) + "." + (ms & 0xFFFF) + "." + (ls >>> 16) + "." + (ls & 0xFFFF);
            }
        }
        return null;
    }

    /** Offset of the sub-directory of the entry with the given integer id in directory at dirOff, or -1. */
    private static long findSubdir(FileChannel ch, long size, long resBase, long dirOff, int id) throws IOException {
        ByteBuffer hdr = read(ch, dirOff, 16, size);
        int named = u16(hdr, 12);
        int ids = u16(hdr, 14);
        if (named + ids > 4096) {
            throw new PeFormatException("malformed resource directory");
        }
        ByteBuffer ents = read(ch, dirOff + 16, (named + ids) * 8, size);
        for (int i = named; i < named + ids; i++) {
            long nameOrId = u32(ents, i * 8);
            long off = u32(ents, i * 8 + 4);
            if (nameOrId == id && (off & 0x80000000L) != 0) {
                return resBase + (off & 0x7FFFFFFFL);
            }
        }
        return -1;
    }

    /** Sub-directory offset of the first entry of the directory at dirOff, or -1. */
    private static long firstEntry(FileChannel ch, long size, long resBase, long dirOff) throws IOException {
        ByteBuffer hdr = read(ch, dirOff, 16, size);
        if (u16(hdr, 12) + u16(hdr, 14) == 0) {
            return -1;
        }
        long off = u32(read(ch, dirOff + 16, 8, size), 4);
        return (off & 0x80000000L) != 0 ? resBase + (off & 0x7FFFFFFFL) : -1;
    }

    /** Offset of the first IMAGE_RESOURCE_DATA_ENTRY below the directory at dirOff, or -1. */
    private static long firstEntryLeaf(FileChannel ch, long size, long resBase, long dirOff) throws IOException {
        ByteBuffer hdr = read(ch, dirOff, 16, size);
        if (u16(hdr, 12) + u16(hdr, 14) == 0) {
            return -1;
        }
        long off = u32(read(ch, dirOff + 16, 8, size), 4);
        return (off & 0x80000000L) == 0 ? resBase + off : -1;
    }

    private static long rvaToFile(ByteBuffer sec, int sections, long rva) {
        for (int i = 0; i < sections; i++) {
            int o = i * 40;
            long virtualSize = u32(sec, o + 8);
            long virtualAddr = u32(sec, o + 12);
            long rawSize = u32(sec, o + 16);
            long rawPtr = u32(sec, o + 20);
            long span = Math.max(virtualSize, rawSize);
            if (rva >= virtualAddr && rva < virtualAddr + span) {
                return rawPtr + (rva - virtualAddr);
            }
        }
        return -1;
    }

    // ---- bounds-checked little-endian reads

    private static ByteBuffer read(FileChannel ch, long pos, int len, long size) throws IOException {
        if (pos < 0 || len < 0 || pos + len > size) {
            throw new PeFormatException("truncated or malformed PE file (read of " + len + " bytes at " + pos + " beyond file size " + size + ")");
        }
        ByteBuffer b = ByteBuffer.allocate(len).order(ByteOrder.LITTLE_ENDIAN);
        while (b.hasRemaining()) {
            int n = ch.read(b, pos + b.position());
            if (n < 0) {
                throw new PeFormatException("unexpected end of file");
            }
        }
        b.flip();
        return b;
    }

    private static int u16(ByteBuffer b, int off) {
        return b.getShort(off) & 0xFFFF;
    }

    private static long u32(ByteBuffer b, int off) {
        return b.getInt(off) & 0xFFFFFFFFL;
    }

    /** Compares dotted versions numerically; missing parts are 0. */
    public static int compareVersions(String a, String b) {
        String[] pa = a.split("\\.");
        String[] pb = b.split("\\.");
        for (int i = 0; i < Math.max(pa.length, pb.length); i++) {
            long x = i < pa.length ? Long.parseLong(pa[i]) : 0;
            long y = i < pb.length ? Long.parseLong(pb[i]) : 0;
            if (x != y) {
                return Long.compare(x, y);
            }
        }
        return 0;
    }
}
