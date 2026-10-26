package io.github.andrexarlay.vulkancompat.shaderhost.iris;

import io.github.andrexarlay.vulkancompat.shaderhost.pack.PackReport;
import io.github.andrexarlay.vulkancompat.shaderhost.pack.PackSource;
import io.github.andrexarlay.vulkancompat.shaderhost.pack.ScanLimits;
import io.github.andrexarlay.vulkancompat.shaderhost.pack.ShaderpackScanner;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Compatibility survey over real shaderpacks on this machine: for every pack and every program folder it uses, runs the
 * host's own validation and build and prints the outcome, so the next host features are chosen by what actually blocks
 * real packs. Runs only with {@code -Dbedray.iris.survey=<pack path>;<pack path>...} (zip files or pack folders); the
 * packs are third-party and never part of the repository.
 */
class IrisRealPackSurveyTest {
    @Test
    void surveyRealPacks() throws Exception {
        String list = System.getProperty("bedray.iris.survey");
        Assumptions.assumeTrue(list != null && !list.isBlank(), "set -Dbedray.iris.survey to run the pack survey");
        List<String> lines = new ArrayList<>();
        for (String entry : list.split(";")) {
            Path pack = Path.of(entry.trim());
            if (!Files.exists(pack)) {
                lines.add("[IRIS-SURVEY] pack=" + pack.getFileName() + " missing");
                continue;
            }
            try (PackSource src = PackSource.open(pack, ScanLimits.defaults())) {
                PackReport report = ShaderpackScanner.scan(src);
                IrisPackOptions.Request request = IrisPackOptions.readRequest(pack);
                List<String> folders = IrisPassGraph.usedFolders(report);
                if (folders.isEmpty()) lines.add("[IRIS-SURVEY] pack=" + report.name() + " no program folders");
                for (String folder : folders) {
                    IrisPassGraph.ParseResult result = IrisPassGraph.build(src, report, request, folder);
                    lines.add("[IRIS-SURVEY] pack=" + report.name() + " folder=" + (folder.isEmpty() ? "<root>" : folder)
                        + " state=" + result.status().state() + " detail=" + result.status().detail());
                }
            } catch (Exception error) {
                lines.add("[IRIS-SURVEY] pack=" + pack.getFileName() + " error=" + error);
            }
        }
        lines.forEach(System.out::println);
    }
}
