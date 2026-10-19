package io.github.andrexarlay.vulkancompat.shaderhost.pack;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** Counts decompressed bytes per entry and per pack; stops reading as soon as a limit would be crossed. */
final class ReadBudget {
    private final ScanLimits limits;
    private final String packName;
    private long totalRead;

    ReadBudget(ScanLimits limits, String packName) {
        this.limits = limits;
        this.packName = packName;
    }

    byte[] readAll(InputStream in, String entry) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        long entryRead = 0;
        int n;
        while ((n = in.read(buf)) > 0) {
            entryRead += n;
            if (entryRead > limits.maxEntryBytes()) {
                throw new PackScanException(packName + ": entry " + entry + " larger than " + limits.maxEntryBytes() + " bytes");
            }
            if (totalRead + entryRead > limits.maxTotalBytes()) {
                throw new PackScanException(packName + ": pack larger than " + limits.maxTotalBytes() + " bytes in total");
            }
            out.write(buf, 0, n);
        }
        totalRead += entryRead;
        return out.toByteArray();
    }
}
