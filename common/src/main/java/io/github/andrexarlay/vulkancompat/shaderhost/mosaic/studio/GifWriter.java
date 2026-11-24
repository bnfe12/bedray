package io.github.andrexarlay.vulkancompat.shaderhost.mosaic.studio;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.nio.file.Path;

/** Animated GIF from frame_00001.png ... with the JDK's own GIF writer (looping, fixed delay). */
final class GifWriter {

    private GifWriter() {}

    static void write(Path dir, int frames, int delayMs, Path out) throws java.io.IOException {
        ImageWriter w = ImageIO.getImageWritersByFormatName("gif").next();
        try (ImageOutputStream os = ImageIO.createImageOutputStream(out.toFile())) {
            w.setOutput(os);
            w.prepareWriteSequence(null);
            for (int i = 1; i <= frames; i++) {
                BufferedImage src = ImageIO.read(dir.resolve(String.format("frame_%05d.png", i)).toFile());
                if (src == null) continue;
                BufferedImage img = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
                img.getGraphics().drawImage(src, 0, 0, null);
                IIOMetadata meta = w.getDefaultImageMetadata(ImageTypeSpecifier.createFromRenderedImage(img), null);
                String fmt = meta.getNativeMetadataFormatName();
                IIOMetadataNode root = (IIOMetadataNode) meta.getAsTree(fmt);
                IIOMetadataNode gce = child(root, "GraphicControlExtension");
                gce.setAttribute("disposalMethod", "none");
                gce.setAttribute("userInputFlag", "FALSE");
                gce.setAttribute("transparentColorFlag", "FALSE");
                gce.setAttribute("delayTime", String.valueOf(Math.max(2, delayMs / 10)));
                gce.setAttribute("transparentColorIndex", "0");
                if (i == 1) {
                    IIOMetadataNode apps = child(root, "ApplicationExtensions");
                    IIOMetadataNode app = new IIOMetadataNode("ApplicationExtension");
                    app.setAttribute("applicationID", "NETSCAPE");
                    app.setAttribute("authenticationCode", "2.0");
                    app.setUserObject(new byte[] {1, 0, 0}); // loop forever
                    apps.appendChild(app);
                }
                meta.setFromTree(fmt, root);
                w.writeToSequence(new IIOImage(img, null, meta), null);
            }
            w.endWriteSequence();
        } finally {
            w.dispose();
        }
    }

    private static IIOMetadataNode child(IIOMetadataNode root, String name) {
        for (int i = 0; i < root.getLength(); i++)
            if (root.item(i).getNodeName().equalsIgnoreCase(name)) return (IIOMetadataNode) root.item(i);
        IIOMetadataNode n = new IIOMetadataNode(name);
        root.appendChild(n);
        return n;
    }
}
