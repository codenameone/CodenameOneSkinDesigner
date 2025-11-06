import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.image.ImageObserver;
import java.awt.image.PixelGrabber;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.imageio.ImageIO;
import javax.imageio.spi.IIORegistry;
import javax.imageio.spi.ImageReaderSpi;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

/**
 * Command line utility that converts a standard Android emulator skin into a
 * Codename One skin archive.
 *
 * <p>The tool expects the path to an Android Virtual Device (AVD) skin
 * directory. The directory should contain the standard {@code layout} file and
 * associated images (portrait/landscape). A {@code hardware.ini} file is used
 * to infer hardware characteristics such as density.</p>
 *
 * <p>Usage:</p>
 * <pre>
 *   java AvdSkinToCodenameOneSkin.java /path/to/avd/skin [output.skin]
 * </pre>
 *
 * <p>If the output file path isn't supplied the converter generates a
 * {@code .skin} file next to the AVD skin directory using the directory's name.</p>
 */
public class AvdSkinToCodenameOneSkin {

    static {
        ensureWebpSupport();
    }

    private static final double TABLET_INCH_THRESHOLD = 6.5d;

    public static void main(String[] args) throws Exception {
        if (args.length == 0 || args.length > 2) {
            System.err.println("Usage: java AvdSkinToCodenameOneSkin.java <avd-skin-dir> [output.skin]");
            System.exit(1);
        }

        Path skinDirectory = Paths.get(args[0]).toAbsolutePath().normalize();
        if (!Files.isDirectory(skinDirectory)) {
            error("Input path %s is not a directory".formatted(skinDirectory));
        }

        Path outputFile;
        if (args.length == 2) {
            outputFile = Paths.get(args[1]).toAbsolutePath().normalize();
        } else {
            outputFile = skinDirectory.getParent().resolve(skinDirectory.getFileName().toString() + ".skin");
        }

        if (Files.exists(outputFile)) {
            error("Output file %s already exists".formatted(outputFile));
        }

        Path layoutFile = findLayoutFile(skinDirectory);
        LayoutInfo layoutInfo = LayoutInfo.parse(layoutFile, skinDirectory);
        HardwareInfo hardwareInfo = HardwareInfo.parse(skinDirectory.resolve("hardware.ini"));

        if (!layoutInfo.hasBothOrientations()) {
            error("Layout file must define portrait and landscape display information");
        }

        DeviceImages portraitImages = buildDeviceImages(skinDirectory, layoutInfo.portrait());
        DeviceImages landscapeImages = buildDeviceImages(skinDirectory, layoutInfo.landscape());

        boolean isTablet = hardwareInfo.isTabletLike(TABLET_INCH_THRESHOLD);
        String overrideNames = isTablet ? "tablet,android,android-tablet" : "phone,android,android-phone";

        Properties props = new Properties();
        props.setProperty("touch", "true");
        props.setProperty("platformName", "and");
        props.setProperty("tablet", Boolean.toString(isTablet));
        props.setProperty("systemFontFamily", "Roboto");
        props.setProperty("proportionalFontFamily", "Roboto");
        props.setProperty("monospaceFontFamily", "Droid Sans Mono");
        props.setProperty("smallFontSize", "11");
        props.setProperty("mediumFontSize", "14");
        props.setProperty("largeFontSize", "20");
        props.setProperty("pixelRatio", String.format(Locale.US, "%.6f", hardwareInfo.pixelRatio()));
        props.setProperty("overrideNames", overrideNames);

        Path parent = outputFile.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(outputFile))) {
            writeEntry(zos, "skin.png", portraitImages.withTransparentDisplay());
            writeEntry(zos, "skin_l.png", landscapeImages.withTransparentDisplay());
            writeEntry(zos, "skin_map.png", portraitImages.overlay());
            writeEntry(zos, "skin_map_l.png", landscapeImages.overlay());
            writeProperties(zos, props);
        }

        System.out.println("Codename One skin created at: " + outputFile);
    }

    private static Path findLayoutFile(Path skinDirectory) {
        Path layout = skinDirectory.resolve("layout");
        if (Files.isRegularFile(layout)) {
            return layout;
        }
        // Some skins place the layout inside a nested directory, search one level deep.
        try {
            List<Path> candidates = new ArrayList<>();
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(skinDirectory)) {
                for (Path child : stream) {
                    if (Files.isRegularFile(child) && child.getFileName().toString().equalsIgnoreCase("layout")) {
                        candidates.add(child);
                    } else if (Files.isDirectory(child)) {
                        Path nested = child.resolve("layout");
                        if (Files.isRegularFile(nested)) {
                            candidates.add(nested);
                        }
                    }
                }
            }
            if (!candidates.isEmpty()) {
                if (candidates.size() > 1) {
                    throw new IllegalStateException("Multiple layout files detected within " + skinDirectory);
                }
                return candidates.get(0);
            }
        } catch (IOException err) {
            throw new UncheckedIOException("Failed to locate layout file", err);
        }
        throw new IllegalStateException("Unable to locate layout file inside " + skinDirectory);
    }

    private static DeviceImages buildDeviceImages(Path skinDir, OrientationInfo orientation) {
        Path imagePath = skinDir.resolve(orientation.imageName());
        if (!Files.isRegularFile(imagePath)) {
            throw new IllegalStateException("Missing image '" + orientation.imageName() + "' for " + orientation.orientation());
        }
        try {
            BufferedImage original = readImage(imagePath);
            if (orientation.display().width() <= 0 || orientation.display().height() <= 0) {
                throw new IllegalStateException("Invalid display dimensions for " + orientation.orientation());
            }
            return new DeviceImages(original, orientation.display());
        } catch (IOException err) {
            throw new UncheckedIOException("Failed to read image " + imagePath, err);
        }
    }

    private static BufferedImage readImage(Path imagePath) throws IOException {
        String lowerName = imagePath.getFileName().toString().toLowerCase(Locale.ROOT);

        BufferedImage standard = ImageIO.read(imagePath.toFile());
        if (standard != null) {
            return standard;
        }

        try (InputStream in = Files.newInputStream(imagePath)) {
            BufferedImage viaStream = ImageIO.read(in);
            if (viaStream != null) {
                return viaStream;
            }
        }

        try (ImageInputStream iis = ImageIO.createImageInputStream(Files.newInputStream(imagePath))) {
            if (iis != null) {
                Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
                if (readers.hasNext()) {
                    ImageReader reader = readers.next();
                    try {
                        iis.seek(0);
                        reader.setInput(iis, true, true);
                        BufferedImage decoded = reader.read(0);
                        if (decoded != null) {
                            return decoded;
                        }
                    } finally {
                        reader.dispose();
                    }
                }
            }
        }

        BufferedImage viaDwebp = decodeWithDwebp(imagePath);
        if (viaDwebp != null) {
            return viaDwebp;
        }

        if (GraphicsEnvironment.isHeadless() && lowerName.endsWith(".webp")) {
            throw new IllegalStateException("WebP decoding requires the 'dwebp' command when running headless. Install the 'webp' package and ensure 'dwebp' is on the PATH.");
        }

        byte[] data = Files.readAllBytes(imagePath);
        Image toolkitImage;
        try {
            toolkitImage = Toolkit.getDefaultToolkit().createImage(data);
        } catch (HeadlessException err) {
            throw new IllegalStateException("Unsupported image format for " + imagePath + " (headless toolkit)", err);
        }
        if (toolkitImage == null) {
            throw new IllegalStateException("Unsupported image format for " + imagePath);
        }
        PixelGrabber grabber = new PixelGrabber(toolkitImage, 0, 0, -1, -1, true);
        try {
            grabber.grabPixels();
        } catch (InterruptedException err) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while decoding " + imagePath, err);
        }
        if (grabber.getStatus() != ImageObserver.ALLBITS) {
            throw new IllegalStateException("Failed to decode image " + imagePath);
        }
        int width = grabber.getWidth();
        int height = grabber.getHeight();
        if (width <= 0 || height <= 0) {
            throw new IllegalStateException("Failed to decode image " + imagePath);
        }
        BufferedImage result = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Object pixels = grabber.getPixels();
        if (!(pixels instanceof int[] rgb)) {
            throw new IllegalStateException("Unsupported pixel model in " + imagePath);
        }
        Graphics2D g = result.createGraphics();
        try {
            g.setComposite(AlphaComposite.Src);
            g.drawImage(toolkitImage, 0, 0, null);
            result.setRGB(0, 0, width, height, rgb, 0, width);
        } finally {
            g.dispose();
        }
        return result;
    }

    private static BufferedImage decodeWithDwebp(Path imagePath) throws IOException {
        String lower = imagePath.getFileName().toString().toLowerCase(Locale.ROOT);
        if (!lower.endsWith(".webp")) {
            return null;
        }
        Path tempFile = null;
        try {
            tempFile = Files.createTempFile("avd-webp-", ".png");
            Process process = new ProcessBuilder("dwebp", imagePath.toString(), "-o", tempFile.toString())
                    .redirectErrorStream(true)
                    .start();
            String output;
            try (InputStream stdout = process.getInputStream()) {
                output = new String(stdout.readAllBytes(), StandardCharsets.UTF_8).trim();
            }
            int exit = process.waitFor();
            if (exit != 0) {
                throw new IOException("dwebp exited with status " + exit + (output.isEmpty() ? "" : ": " + output));
            }
            try (InputStream pngStream = Files.newInputStream(tempFile)) {
                BufferedImage converted = ImageIO.read(pngStream);
                if (converted == null) {
                    throw new IOException("dwebp produced an unreadable PNG for " + imagePath);
                }
                return converted;
            }
        } catch (IOException err) {
            String message = err.getMessage();
            if (message != null && message.contains("No such file or directory")) {
                System.err.println("Warning: dwebp command not available. Install the 'webp' package to enable WebP decoding.");
                return null;
            }
            throw err;
        } catch (InterruptedException err) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while running dwebp for " + imagePath, err);
        } finally {
            if (tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (IOException ignored) {
                }
            }
        }
    }

    private static boolean ensureWebpSupport() {
        try {
            ImageIO.setUseCache(false);
            ImageIO.scanForPlugins();
            if (hasWebpReader()) {
                return true;
            }
            Class<?> spiClass = Class.forName("jdk.imageio.webp.WebPImageReaderSpi");
            Object instance = spiClass.getDeclaredConstructor().newInstance();
            if (instance instanceof ImageReaderSpi spi) {
                IIORegistry registry = IIORegistry.getDefaultInstance();
                registry.registerServiceProvider(spi);
            }
        } catch (ClassNotFoundException err) {
            return hasWebpReader();
        } catch (ReflectiveOperationException | LinkageError err) {
            System.err.println("Warning: Unable to initialise WebP decoder: " + err.getMessage());
            return hasWebpReader();
        }
        return hasWebpReader();
    }

    private static boolean hasWebpReader() {
        return ImageIO.getImageReadersBySuffix("webp").hasNext()
                || ImageIO.getImageReadersByMIMEType("image/webp").hasNext();
    }

    private static void writeEntry(ZipOutputStream zos, String name, BufferedImage image) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        zos.putNextEntry(entry);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        javax.imageio.ImageIO.write(image, "png", baos);
        zos.write(baos.toByteArray());
        zos.closeEntry();
    }

    private static void writeProperties(ZipOutputStream zos, Properties props) throws IOException {
        ZipEntry entry = new ZipEntry("skin.properties");
        zos.putNextEntry(entry);
        String comment = "Created by AvdSkinToCodenameOneSkin on " + LocalDateTime.now().format(DateTimeFormatter.ISO_DATE_TIME);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        props.store(baos, comment);
        zos.write(baos.toByteArray());
        zos.closeEntry();
    }

    private static void error(String message) {
        System.err.println("Error: " + message);
        System.exit(1);
    }

    private record DeviceImages(BufferedImage original, DisplayArea display) {
        BufferedImage withTransparentDisplay() {
            BufferedImage result = new BufferedImage(original.getWidth(), original.getHeight(), BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = result.createGraphics();
            try {
                g.setComposite(AlphaComposite.Src);
                g.drawImage(original, 0, 0, null);
                g.setComposite(AlphaComposite.Clear);
                g.fillRect(display.x(), display.y(), display.width(), display.height());
            } finally {
                g.dispose();
            }
            return result;
        }

        BufferedImage overlay() {
            BufferedImage overlay = new BufferedImage(original.getWidth(), original.getHeight(), BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = overlay.createGraphics();
            try {
                g.setComposite(AlphaComposite.Src);
                g.setColor(Color.BLACK);
                g.fillRect(display.x(), display.y(), display.width(), display.height());
            } finally {
                g.dispose();
            }
            return overlay;
        }
    }

    private record DisplayArea(int x, int y, int width, int height) {}

    private enum OrientationType {
        PORTRAIT,
        LANDSCAPE
    }

    private record OrientationInfo(OrientationType orientation, String imageName, DisplayArea display) {}

    private record LayoutInfo(OrientationInfo portrait, OrientationInfo landscape) {
        boolean hasBothOrientations() {
            return portrait != null && landscape != null;
        }

        static LayoutInfo parse(Path layoutFile, Path skinDirectory) {
            try {
                return new LayoutParser(layoutFile, skinDirectory).parse(Files.readString(layoutFile));
            } catch (IOException err) {
                throw new UncheckedIOException("Failed to read layout file " + layoutFile, err);
            }
        }
    }

    private static class LayoutParser {
        private final EnumMap<OrientationType, OrientationInfoBuilder> builders = new EnumMap<>(OrientationType.class);
        private final Deque<Context> contextStack = new ArrayDeque<>();
        private final Path skinDirectory;
        private final Path layoutParent;
        private Integer baseDisplayX;
        private Integer baseDisplayY;
        private Integer baseDisplayWidth;
        private Integer baseDisplayHeight;

        LayoutParser(Path layoutFile, Path skinDirectory) {
            this.skinDirectory = skinDirectory;
            this.layoutParent = layoutFile.getParent();
        }

        LayoutInfo parse(String text) {
            String[] lines = text.split("\r?\n");
            for (String rawLine : lines) {
                String line = stripComments(rawLine).trim();
                if (line.isEmpty()) {
                    continue;
                }
                if (line.endsWith("{")) {
                    pushContext(line.substring(0, line.length() - 1).trim());
                } else if (line.equals("}")) {
                    popContext();
                } else {
                    handleKeyValue(line);
                }
            }
            OrientationInfo portrait = builders.containsKey(OrientationType.PORTRAIT)
                    ? builders.get(OrientationType.PORTRAIT).build(OrientationType.PORTRAIT, this)
                    : null;
            OrientationInfo landscape = builders.containsKey(OrientationType.LANDSCAPE)
                    ? builders.get(OrientationType.LANDSCAPE).build(OrientationType.LANDSCAPE, this)
                    : null;
            return new LayoutInfo(portrait, landscape);
        }

        private void handleKeyValue(String line) {
            Context ctx = contextStack.peek();
            if (ctx == null) {
                return;
            }
            OrientationType orientation = findCurrentOrientation();

            String[] parts = splitKeyValue(line);
            if (parts == null) {
                return;
            }
            String key = parts[0];
            String value = unquote(parts[1]);

            if (orientation == null && isInDeviceDisplayContext()) {
                switch (key.toLowerCase(Locale.ROOT)) {
                    case "x" -> baseDisplayX = parseInt(value);
                    case "y" -> baseDisplayY = parseInt(value);
                    case "width" -> baseDisplayWidth = parseInt(value);
                    case "height" -> baseDisplayHeight = parseInt(value);
                }
                return;
            }

            if (orientation == null) {
                if (ctx.isPartBlock && key.equalsIgnoreCase("name")) {
                    ctx.devicePart = value.equalsIgnoreCase("device");
                }
                return;
            }
            OrientationInfoBuilder builder = builders.computeIfAbsent(orientation, o -> new OrientationInfoBuilder());
            String ctxName = ctx.name.toLowerCase(Locale.ROOT);
            if (ctx.isPartBlock && key.equalsIgnoreCase("name")) {
                ctx.devicePart = value.equalsIgnoreCase("device");
            }
            if (isImageKey(key) && shouldTreatAsImage(ctx, key)) {
                builder.considerImage(value, contextStack, this::resolveImagePath);
            } else if (ctxName.contains("display")) {
                switch (key.toLowerCase(Locale.ROOT)) {
                    case "x" -> builder.displayXOverride = parseInt(value);
                    case "y" -> builder.displayYOverride = parseInt(value);
                    case "width" -> builder.displayWidthOverride = parseInt(value);
                    case "height" -> builder.displayHeightOverride = parseInt(value);
                }
            } else if (isInDevicePartContext()) {
                switch (key.toLowerCase(Locale.ROOT)) {
                    case "x" -> builder.offsetX = parseInt(value);
                    case "y" -> builder.offsetY = parseInt(value);
                    case "rotation" -> builder.rotation = parseInt(value);
                }
            }
        }

        private boolean isImageKey(String key) {
            String lower = key.toLowerCase(Locale.ROOT);
            return lower.equals("name") || lower.equals("image") || lower.equals("filename");
        }

        private boolean shouldTreatAsImage(Context ctx, String key) {
            if (!key.equalsIgnoreCase("name")) {
                return true;
            }
            String ctxName = ctx.name.toLowerCase(Locale.ROOT);
            return ctxName.contains("image")
                    || ctxName.contains("background")
                    || ctxName.contains("foreground")
                    || ctxName.contains("frame")
                    || ctxName.contains("skin")
                    || ctxName.contains("device")
                    || ctxName.contains("phone")
                    || ctxName.contains("tablet")
                    || ctxName.contains("onion")
                    || ctxName.contains("overlay");
        }

        private void pushContext(String name) {
            name = name.trim();
            if (name.isEmpty()) {
                contextStack.push(new Context("", null));
                return;
            }
            OrientationType orientation = detectOrientation(name);
            contextStack.push(new Context(name, orientation));
        }

        private void popContext() {
            if (!contextStack.isEmpty()) {
                contextStack.pop();
            }
        }

        private OrientationType findCurrentOrientation() {
            for (Context ctx : contextStack) {
                if (ctx.orientation != null) {
                    return ctx.orientation;
                }
            }
            return null;
        }

        private String[] splitKeyValue(String line) {
            String cleaned = line.replace('=', ' ');
            String[] parts = cleaned.trim().split("\\s+", 2);
            if (parts.length != 2) {
                return null;
            }
            return parts;
        }

        private String unquote(String value) {
            value = value.trim();
            if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                return value.substring(1, value.length() - 1);
            }
            if (value.length() >= 2 && value.startsWith("'") && value.endsWith("'")) {
                return value.substring(1, value.length() - 1);
            }
            return value;
        }

        private int parseInt(String value) {
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException err) {
                throw new IllegalStateException("Invalid integer value '" + value + "' in layout file", err);
            }
        }

        private Path resolveImagePath(String name) {
            Path candidate = skinDirectory.resolve(name).normalize();
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            if (layoutParent != null) {
                Path sibling = layoutParent.resolve(name).normalize();
                if (Files.isRegularFile(sibling)) {
                    return sibling;
                }
            }
            return candidate;
        }

        private String stripComments(String line) {
            int slash = line.indexOf("//");
            int hash = line.indexOf('#');
            int cut = -1;
            if (slash >= 0) {
                cut = slash;
            }
            if (hash >= 0 && (cut < 0 || hash < cut)) {
                cut = hash;
            }
            if (cut >= 0) {
                return line.substring(0, cut);
            }
            return line;
        }

        private OrientationType detectOrientation(String name) {
            String lower = name.toLowerCase(Locale.ROOT);
            if (lower.contains("land") || lower.contains("horz")) {
                return OrientationType.LANDSCAPE;
            }
            if (lower.contains("port") || lower.contains("vert")) {
                return OrientationType.PORTRAIT;
            }
            return null;
        }

        private boolean isInDeviceDisplayContext() {
            Iterator<Context> it = contextStack.iterator();
            if (!it.hasNext()) {
                return false;
            }
            Context top = it.next();
            if (!top.name.equalsIgnoreCase("display")) {
                return false;
            }
            if (!it.hasNext()) {
                return false;
            }
            Context parent = it.next();
            return parent.name.equalsIgnoreCase("device");
        }

        private boolean isInDevicePartContext() {
            for (Context ctx : contextStack) {
                if (ctx.isPartBlock && ctx.devicePart) {
                    return true;
                }
            }
            return false;
        }

        private static final class Context {
            final String name;
            final OrientationType orientation;
            final boolean isPartBlock;
            boolean devicePart;

            Context(String name, OrientationType orientation) {
                this.name = name;
                this.orientation = orientation;
                this.isPartBlock = name != null && name.toLowerCase(Locale.ROOT).startsWith("part");
            }
        }

        private static class OrientationInfoBuilder {
            ImageCandidate selectedImage;
            Integer displayXOverride;
            Integer displayYOverride;
            Integer displayWidthOverride;
            Integer displayHeightOverride;
            Integer offsetX;
            Integer offsetY;
            Integer rotation;

            void considerImage(String name, Deque<Context> contexts, java.util.function.Function<String, Path> resolver) {
                ImageCandidate candidate = ImageCandidate.from(name, contexts, resolver);
                if (selectedImage == null || candidate.isBetterThan(selectedImage)) {
                    selectedImage = candidate;
                }
            }

            OrientationInfo build(OrientationType type, LayoutParser parser) {
                if (selectedImage == null) {
                    throw new IllegalStateException("Layout definition for " + type + " is incomplete");
                }
                int baseX = parser.baseDisplayX != null ? parser.baseDisplayX : 0;
                int baseY = parser.baseDisplayY != null ? parser.baseDisplayY : 0;
                Integer widthSource = displayWidthOverride != null ? displayWidthOverride : parser.baseDisplayWidth;
                Integer heightSource = displayHeightOverride != null ? displayHeightOverride : parser.baseDisplayHeight;
                if (widthSource == null || heightSource == null) {
                    throw new IllegalStateException("Layout definition for " + type + " is missing display dimensions");
                }
                int finalWidth = widthSource;
                int finalHeight = heightSource;
                int normalizedRotation = rotation != null ? Math.floorMod(rotation, 4) : 0;
                if ((normalizedRotation & 1) == 1) {
                    int tmp = finalWidth;
                    finalWidth = finalHeight;
                    finalHeight = tmp;
                }
                int finalX = displayXOverride != null ? displayXOverride : baseX;
                int finalY = displayYOverride != null ? displayYOverride : baseY;
                if (offsetX != null) {
                    finalX += offsetX;
                }
                if (offsetY != null) {
                    finalY += offsetY;
                }
                return new OrientationInfo(type, selectedImage.name(), new DisplayArea(finalX, finalY, finalWidth, finalHeight));
            }
        }

        private record ImageCandidate(String name, long area, boolean frameHint, boolean controlHint) {
            static ImageCandidate from(String name, Deque<Context> contexts, java.util.function.Function<String, Path> resolver) {
                boolean frameHint = false;
                boolean controlHint = false;
                for (Context ctx : contexts) {
                    String lower = ctx.name.toLowerCase(Locale.ROOT);
                    if (lower.contains("button") || lower.contains("control") || lower.contains("icon") || lower.contains("touch") || lower.contains("shadow") || lower.contains("onion")) {
                        controlHint = true;
                    }
                    if (lower.contains("device") || lower.contains("frame") || lower.contains("skin") || lower.contains("phone") || lower.contains("tablet") || lower.contains("background") || lower.contains("back")) {
                        frameHint = true;
                    }
                }
                String lowerName = name.toLowerCase(Locale.ROOT);
                if (lowerName.contains("frame") || lowerName.contains("device") || lowerName.contains("shell") || lowerName.contains("body") || lowerName.contains("background") || lowerName.contains("back") || lowerName.contains("fore")) {
                    frameHint = true;
                }
                if (lowerName.contains("button") || lowerName.contains("control") || lowerName.contains("icon") || lowerName.contains("shadow") || lowerName.contains("onion")) {
                    controlHint = true;
                }
                long area = computeArea(resolver.apply(name));
                return new ImageCandidate(name, area, frameHint, controlHint);
            }

            private static long computeArea(Path imagePath) {
                if (imagePath == null || !Files.isRegularFile(imagePath)) {
                    return -1;
                }
                try {
                    BufferedImage img = javax.imageio.ImageIO.read(imagePath.toFile());
                    if (img == null) {
                        return -1;
                    }
                    return (long) img.getWidth() * (long) img.getHeight();
                } catch (IOException err) {
                    return -1;
                }
            }

            boolean isBetterThan(ImageCandidate other) {
                if (other == null) {
                    return true;
                }
                if (frameHint != other.frameHint) {
                    return frameHint && !controlHint;
                }
                if (controlHint != other.controlHint) {
                    return !controlHint;
                }
                long thisArea = Math.max(area, 0);
                long otherArea = Math.max(other.area, 0);
                if (thisArea != otherArea) {
                    return thisArea > otherArea;
                }
                return name.compareTo(other.name) < 0;
            }
        }
    }

    private record HardwareInfo(int widthPixels, int heightPixels, double densityDpi) {
        double pixelRatio() {
            if (densityDpi <= 0) {
                return 6.0d;
            }
            return densityDpi / 25.4d;
        }

        boolean isTabletLike(double diagonalInchThreshold) {
            double density = densityDpi > 0 ? densityDpi : 320d;
            double widthInches = widthPixels / density;
            double heightInches = heightPixels / density;
            double diagonal = Math.hypot(widthInches, heightInches);
            return diagonal >= diagonalInchThreshold;
        }

        static HardwareInfo parse(Path hardwareIni) {
            Properties props = new Properties();
            if (Files.isRegularFile(hardwareIni)) {
                try (BufferedReader reader = Files.newBufferedReader(hardwareIni)) {
                    props.load(reader);
                } catch (IOException err) {
                    throw new UncheckedIOException("Failed to read hardware.ini", err);
                }
            }
            int width = parseInt(props.getProperty("hw.lcd.width"), 1080);
            int height = parseInt(props.getProperty("hw.lcd.height"), 1920);
            double density = parseDouble(props.getProperty("hw.lcd.density"),
                    parseDouble(props.getProperty("hw.lcd.pixelDensity"), 420d));
            return new HardwareInfo(width, height, density);
        }

        private static int parseInt(String value, int defaultValue) {
            if (value == null || value.isEmpty()) {
                return defaultValue;
            }
            try {
                return Integer.parseInt(value.trim());
            } catch (NumberFormatException err) {
                return defaultValue;
            }
        }

        private static double parseDouble(String value, double defaultValue) {
            if (value == null || value.isEmpty()) {
                return defaultValue;
            }
            try {
                return Double.parseDouble(value.trim());
            } catch (NumberFormatException err) {
                return defaultValue;
            }
        }
    }
}
