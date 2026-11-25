package io.github.andrexarlay.vulkancompat.shaderhost.mosaic.studio;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;

import static io.github.andrexarlay.vulkancompat.shaderhost.mosaic.studio.StudioUi.*;

/** The built-in guide: how the editor works, from the first node to a pass of Bedray rewritten in nodes. */
final class GuidePanel implements Panels.Panel {
    final JPanel root = new JPanel(new BorderLayout(8, 0));
    final JEditorPane page = new JEditorPane("text/html", "");
    final JList<String> toc;

    static final String[][] SECTIONS = {
        {"Start", """
            <h2>Mosaic Studio</h2>
            <p>Studio is where Bedray is built from nodes. Three workspaces sit in the top bar:</p>
            <ul>
            <li><b>Shaders</b>: post effects and whole frames as graphs (GLSL / Slang), with a GPU preview and the game's own frame.</li>
            <li><b>Materials</b>: the surface of every block, item and entity texture (PBR maps, paint, UV).</li>
            <li><b>Renderer</b>: Bedray itself. The frame is a chain of stages; each stage opens into the modules and functions it is made of,
            and each function opens into its body as nodes.</li>
            </ul>
            <p>Tabs work like Photoshop's: a texture, an entity, a shader and a Bedray function can be open side by side.
            <b>+</b> next to the tabs makes a new one (shader, material, renderer graph, the pack map, a Bedray function).</p>
            <p>F1 lists every key. Ctrl+Z / Ctrl+Shift+Z undo and redo; History shows every step by name.</p>"""},
        {"Nodes and wires", """
            <h2>Nodes and wires</h2>
            <ul>
            <li><b>Add</b>: Shift+A or double-click empty canvas: a searchable menu. Or drag from the Nodes panel.</li>
            <li><b>Wire</b>: drag from an output socket (right) to an input (left). Dropping a wire on empty canvas offers a node for it.</li>
            <li><b>Socket colours</b>: grey float, green vec2, amber vec3, violet vec4, teal a Slang value (a struct such as SurfaceHit:
            it connects only to its own type).</li>
            <li><b>Inline values</b>: an unwired input keeps a value of its own (the inspector edits it).</li>
            <li><b>Mute</b>: M passes a node's input straight through (or zero), like Blender.</li>
            <li><b>Arrange</b>: select nodes and use Node > Arrange; dragging a node to the edge pans the view.</li>
            <li><b>Frames</b> group nodes under a title; <b>notes</b> (grey text under a node) are developer comments: write your own in the
            inspector, Bedray's own comments come with its functions.</li>
            </ul>"""},
        {"Renderer: stages", """
            <h2>The Bedray renderer as nodes</h2>
            <p>The Renderer workspace shows the frame as a chain: Scene, Materials, Sun and sky, Voxels, then <b>one render mode</b>
            (Raster, Path tracer or Hybrid ray effects), the reuse stages (ReSTIR DI, ReSTIR PT, Radiance Cascades), fog, the denoiser,
            the upscaler, exposure and tone, post effects, and the Bedray frame.</p>
            <ul>
            <li>Wiring a stage in turns it on; the wired render mode is Bedray's mode. Two modes in one frame is an error.</li>
            <li>Every Bedray setting is on a stage (pixel shadows on Hybrid ray effects, FSR / XeSS / DLSS on Upscaler, voxel distance on
            Voxels...). The inspector edits them with their real ranges and help.</li>
            <li><b>Double-click a stage</b> to open its inside: its settings as public parameters, and every module of Bedray it is made of,
            each function a node. Untouched it is Bedray's own; once you edit a function in it, it shows as <i>your version</i>.</li>
            </ul>"""},
        {"Functions as nodes", """
            <h2>Every function of the pack is a node</h2>
            <p>All of Bedray's Slang pack (122 modules, about 650 functions, 150 structs, 380 constants) is a node library.
            Double-click a function node (or <b>+ > Bedray function as nodes</b>) and its body opens as a graph:</p>
            <ul>
            <li><b>Parameters</b> (violet, left) and the <b>Return</b> (violet, right); out parameters are their own ends.</li>
            <li><b>Variables</b> (green) are named values, one node per assignment, so you can follow a value through the function.</li>
            <li><b>Pack calls</b> (blue) are other Bedray functions: open them the same way, all the way down.</li>
            <li><b>Operators, calls, fields</b> are plain Slang pieces; their inspector edits the operator, the function name or the field.</li>
            <li><b>Slang blocks</b> (amber) are loops and branches: they stay code, with ports for the variables they read and change.
            Edit the code in the inspector (Ctrl+Enter applies).</li>
            <li>An early <code>if (...) return x;</code> becomes a choice node, so the rest stays nodes.</li>
            </ul>
            <p>After every change slangc checks the function inside its module; the status bar says whether Bedray's pack still compiles.</p>"""},
        {"Apply to Bedray", """
            <h2>Running your version in the game</h2>
            <ol>
            <li>Edit a function (or several). Your graphs are kept per function; <b>Reset to Bedray's</b> throws one away.</li>
            <li><b>Apply to Bedray</b> (inspector of a function) writes the changed modules to <code>&lt;game&gt;/bedray-studio/slang</code> and
            rebuilds every pass that uses them with Bedray's own slangc settings (compute, ray-generation and SER variants).</li>
            <li>A pass that would read resources Bedray does not give it is refused, so the game never gets a pass it cannot bind.</li>
            <li>Bedray loads <code>bedray-studio/passes</code> before its built-in passes, from its next renderer start.
            <b>Remove Studio passes</b> gives the game its own back. <code>-Dbedray.studioPasses=false</code> ignores them.</li>
            </ol>"""},
        {"Config and priority", """
            <h2>Public parameters and the config</h2>
            <p>Every stage setting is a public number, like a public field in Unity: the <b>Config</b> panel (Window > Config) lists all of
            them stage by stage, edits them, copies or saves them as <code>bedray.toml</code> lines, and sends them to the game.</p>
            <p>Studio sends them with <b>priority</b>: while Live is on, the game puts Studio's numbers back if its menu changes them.
            Turn Live off to let the menu decide again.</p>"""},
        {"Materials", """
            <h2>Materials</h2>
            <ul>
            <li>The Library finds Minecraft instances of every launcher (26.3 first) and their textures, resource packs and mods.</li>
            <li>Open a texture: its material graph (Image, Texture, BSDF, Material output) bakes LabPBR maps (_s, _n) into the Mosaic
            Materials resource pack the game reloads.</li>
            <li>The preview shows the real vanilla model (blocks, items face-on, entities with their own model), the UV layout with seams,
            and paints on the texture or the model.</li>
            </ul>"""},
        {"Shaders and passes", """
            <h2>Shader graphs</h2>
            <ul>
            <li>A shader graph ends in an Output; Studio writes GLSL or Slang, compiles it and previews it on the GPU.</li>
            <li><b>Buffer</b> nodes split a graph into passes (bloom and blur chains); History reads the last frame.</li>
            <li>World nodes read the scene: depth, normals, materials, sun direction, rain, thunder, temperature, sky and fog colour.</li>
            <li>The Shader browser imports Iris packs and exports graphs.</li>
            </ul>"""},
        {"AI agent", """
            <h2>The agent</h2>
            <p>Settings > Agent gives an AI agent a command line over the project: it reads and edits graphs, nodes and the design,
            within the permissions you set there.</p>"""}};

    GuidePanel(StudioModel m) {
        root.setBackground(PANEL);
        root.setBorder(new EmptyBorder(8, 8, 8, 8));
        String[] names = new String[SECTIONS.length];
        for (int i = 0; i < names.length; i++) names[i] = SECTIONS[i][0];
        toc = new JList<>(names);
        toc.setFont(UI);
        toc.setBackground(PANEL2);
        toc.setForeground(TEXT);
        toc.setSelectionBackground(SELECT.darker());
        toc.setFixedCellHeight(26);
        toc.addListSelectionListener(e -> show(toc.getSelectedIndex()));
        JScrollPane left = scroll(toc);
        left.setPreferredSize(new Dimension(170, 200));
        page.setEditable(false);
        page.setBackground(PANEL);
        page.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
        page.setFont(UI);
        root.add(left, BorderLayout.WEST);
        root.add(scroll(page), BorderLayout.CENTER);
        toc.setSelectedIndex(0);
    }

    private void show(int i) {
        if (i < 0) return;
        String css = "<style>body{font-family:sans-serif;color:#D8DCE6;margin:6px 10px;font-size:11px}h2{color:#FFFFFF;font-size:15px;margin-bottom:6px}"
            + "b{color:#FFFFFF}code{color:#E0B45C}li{margin-bottom:4px}i{color:#E0B45C}</style>";
        page.setText("<html><head>" + css + "</head><body>" + SECTIONS[i][1] + "</body></html>");
        page.setCaretPosition(0);
    }

    public JComponent component() { return root; }
}
