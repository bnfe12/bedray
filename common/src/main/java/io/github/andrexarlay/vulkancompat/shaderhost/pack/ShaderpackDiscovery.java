package io.github.andrexarlay.vulkancompat.shaderhost.pack;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Lists shaderpack candidates in a shaderpacks/ folder: {@code *.zip} files and folders that contain {@code shaders/}
 * (Iris {@code isValidShaderpack} for folders). Symbolic links are not followed. Option files ({@code <pack>.txt}) and anything
 * else are ignored. Listing does not open zips; {@link PackReportCache} does that on demand.
 */
public final class ShaderpackDiscovery {
    /** Upper bound of listed candidates (a shaderpacks folder with more is a misconfiguration, not a pack collection). */
    public static final int MAX_CANDIDATES = 1024;

    public record Candidate(Path path, PackSource.Kind kind) {
        public String name() {
            return path.getFileName().toString();
        }
    }

    private ShaderpackDiscovery() {}

    public static List<Candidate> list(Path shaderpacksDir) throws IOException {
        List<Candidate> out = new ArrayList<>();
        if (!Files.isDirectory(shaderpacksDir, LinkOption.NOFOLLOW_LINKS)) {
            return out;
        }
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(shaderpacksDir)) {
            for (Path p : ds) {
                if (Files.isSymbolicLink(p)) {
                    continue;
                }
                String n = p.getFileName().toString();
                if (Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS) && n.toLowerCase(Locale.ROOT).endsWith(".zip")) {
                    out.add(new Candidate(p, PackSource.Kind.ZIP));
                } else if (Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)
                    && Files.isDirectory(p.resolve("shaders"), LinkOption.NOFOLLOW_LINKS)) {
                    out.add(new Candidate(p, PackSource.Kind.DIRECTORY));
                }
                if (out.size() > MAX_CANDIDATES) {
                    throw new PackScanException("more than " + MAX_CANDIDATES + " entries in " + shaderpacksDir);
                }
            }
        }
        out.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
        return out;
    }

    /**
     * Resolves a pack name chosen by the user (iris.properties {@code shaderPack}) to a path in shaderpacks/. Only a plain file or
     * folder name is accepted: separators, '..', ':' and control characters are refused, so the choice cannot point elsewhere.
     */
    public static Path resolveChoice(Path shaderpacksDir, String packName) throws PackScanException {
        if (packName == null || packName.isBlank() || packName.equals(".") || packName.equals("..")
            || packName.indexOf('/') >= 0 || packName.indexOf('\\') >= 0 || packName.indexOf(':') >= 0) {
            throw new PackScanException("invalid shaderpack name: " + PackPaths.printable(String.valueOf(packName)));
        }
        for (int i = 0; i < packName.length(); i++) {
            if (packName.charAt(i) < 0x20) {
                throw new PackScanException("invalid shaderpack name: " + PackPaths.printable(packName));
            }
        }
        Path p = shaderpacksDir.resolve(packName).normalize();
        if (!p.getParent().equals(shaderpacksDir.normalize())) {
            throw new PackScanException("shaderpack name leaves shaderpacks/: " + packName);
        }
        return p;
    }
}
