package io.github.andrexarlay.vulkanbridge.installer;

import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Desktop;
import java.awt.GraphicsEnvironment;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;

/**
 * {@code java -jar bedray-*.jar} opens a small installer that copies this jar into a game folder. The same jar is the mod, so
 * the loaders ignore this entry point. Command line (also used when no display is available):
 * <pre>
 *   java -jar bedray-*.jar --list                 lists game folders found
 *   java -jar bedray-*.jar --install &lt;gameDir&gt;     installs into a folder
 * </pre>
 * Vendor DLLs (NVIDIA, AMD, Intel) are never bundled or downloaded by the installer; it only points to the official pages.
 */
public final class BedrayInstaller {
    private static final String DOWNLOADS = "https://bnfe12.github.io/bedray/DOWNLOADS.html";

    private BedrayInstaller() {
    }

    public static void main(String[] args) {
        Path self = ownJar();
        if (args.length > 0) {
            System.exit(cli(args, self));
            return;
        }
        if (GraphicsEnvironment.isHeadless()) {
            System.out.println("No display. Use --list or --install <gameDir>.");
            System.exit(cli(new String[] {"--list"}, self));
            return;
        }
        SwingUtilities.invokeLater(() -> window(self));
    }

    static int cli(String[] args, Path self) {
        if ("--list".equals(args[0])) {
            List<InstallTargets.Target> targets = InstallTargets.detect(System.getenv());
            if (targets.isEmpty()) {
                System.out.println("No game folders found. Use --install <gameDir> with the folder that contains mods/.");
            }
            for (InstallTargets.Target t : targets) {
                System.out.println(t.label() + "  " + t.gameDir());
            }
            return 0;
        }
        if ("--install".equals(args[0]) && args.length >= 2) {
            JarInstaller.Result result = JarInstaller.install(self, Path.of(args[1]));
            result.messages().forEach(System.out::println);
            return result.ok() ? 0 : 1;
        }
        System.out.println("Usage: --list | --install <gameDir>");
        return 2;
    }

    private static Path ownJar() {
        try {
            return Path.of(BedrayInstaller.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (Exception e) {
            return null;
        }
    }

    private static void window(Path self) {
        JFrame frame = new JFrame("Bedray installer");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        JComboBox<InstallTargets.Target> choice = new JComboBox<>();
        for (InstallTargets.Target t : InstallTargets.detect(System.getenv())) {
            choice.addItem(t);
        }
        choice.setRenderer((list, value, index, selected, focus) ->
            new JLabel(value == null ? "" : value.label() + "  -  " + value.gameDir()));
        JTextArea log = new JTextArea(10, 60);
        log.setEditable(false);
        log.setText("Pick the game folder (the one that contains mods) and press Install.\n"
            + "Bedray never downloads vendor DLLs by itself: press \"Official DLL pages\" to get them from the vendors.\n");
        JButton browse = new JButton("Other folder...");
        browse.addActionListener(e -> {
            JFileChooser chooser = new JFileChooser();
            chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            if (chooser.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) {
                InstallTargets.Target picked = new InstallTargets.Target("Chosen folder", chooser.getSelectedFile().toPath());
                choice.addItem(picked);
                choice.setSelectedItem(picked);
            }
        });
        JButton install = new JButton("Install");
        install.addActionListener(e -> {
            InstallTargets.Target t = (InstallTargets.Target) choice.getSelectedItem();
            if (t == null) {
                log.append("Choose a game folder first.\n");
                return;
            }
            JarInstaller.Result result = JarInstaller.install(self, t.gameDir());
            result.messages().forEach(m -> log.append(m + "\n"));
        });
        JButton dlls = new JButton("Official DLL pages");
        dlls.addActionListener(e -> {
            try {
                Desktop.getDesktop().browse(URI.create(DOWNLOADS));
            } catch (Exception failure) {
                log.append("Open this page in a browser: " + DOWNLOADS + "\n");
            }
        });
        JPanel top = new JPanel(new BorderLayout(6, 6));
        top.add(choice, BorderLayout.CENTER);
        top.add(browse, BorderLayout.EAST);
        JPanel bottom = new JPanel();
        bottom.add(install);
        bottom.add(dlls);
        frame.add(top, BorderLayout.NORTH);
        frame.add(new JScrollPane(log), BorderLayout.CENTER);
        frame.add(bottom, BorderLayout.SOUTH);
        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }
}
