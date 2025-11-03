import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

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

        LayoutInfo layoutInfo = LayoutInfo.parse(findLayoutFile(skinDirectory));
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
            BufferedImage original = javax.imageio.ImageIO.read(imagePath.toFile());
            if (original == null) {
                throw new IllegalStateException("Failed to decode image " + imagePath);
            }
            if (orientation.display().width() <= 0 || orientation.display().height() <= 0) {
                throw new IllegalStateException("Invalid display dimensions for " + orientation.orientation());
            }
            return new DeviceImages(original, orientation.display());
        } catch (IOException err) {
            throw new UncheckedIOException("Failed to read image " + imagePath, err);
        }
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

        static LayoutInfo parse(Path layoutFile) {
            try {
                return new LayoutParser().parse(Files.readString(layoutFile));
            } catch (IOException err) {
                throw new UncheckedIOException("Failed to read layout file " + layoutFile, err);
            }
        }
    }

    private static class LayoutParser {
        private final EnumMap<OrientationType, OrientationInfoBuilder> builders = new EnumMap<>(OrientationType.class);
        private final Deque<Context> contextStack = new ArrayDeque<>();

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
                    ? builders.get(OrientationType.PORTRAIT).build(OrientationType.PORTRAIT)
                    : null;
            OrientationInfo landscape = builders.containsKey(OrientationType.LANDSCAPE)
                    ? builders.get(OrientationType.LANDSCAPE).build(OrientationType.LANDSCAPE)
                    : null;
            return new LayoutInfo(portrait, landscape);
        }

        private void handleKeyValue(String line) {
            Context ctx = contextStack.peek();
            if (ctx == null) {
                return;
            }
            OrientationType orientation = findCurrentOrientation();
            if (orientation == null) {
                return;
            }
            OrientationInfoBuilder builder = builders.computeIfAbsent(orientation, o -> new OrientationInfoBuilder());
            String[] parts = splitKeyValue(line);
            if (parts == null) {
                return;
            }
            String key = parts[0];
            String value = parts[1];
            String ctxName = ctx.name.toLowerCase(Locale.ROOT);
            if (ctxName.contains("image") && key.equalsIgnoreCase("name")) {
                builder.imageName = value;
            } else if (ctxName.contains("display")) {
                switch (key.toLowerCase(Locale.ROOT)) {
                    case "x" -> builder.displayX = parseInt(value);
                    case "y" -> builder.displayY = parseInt(value);
                    case "width" -> builder.displayWidth = parseInt(value);
                    case "height" -> builder.displayHeight = parseInt(value);
                }
            }
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

        private int parseInt(String value) {
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException err) {
                throw new IllegalStateException("Invalid integer value '" + value + "' in layout file", err);
            }
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

        private record Context(String name, OrientationType orientation) {}

        private static class OrientationInfoBuilder {
            String imageName;
            Integer displayX;
            Integer displayY;
            Integer displayWidth;
            Integer displayHeight;

            OrientationInfo build(OrientationType type) {
                if (imageName == null || displayX == null || displayY == null || displayWidth == null || displayHeight == null) {
                    throw new IllegalStateException("Layout definition for " + type + " is incomplete");
                }
                return new OrientationInfo(type, imageName, new DisplayArea(displayX, displayY, displayWidth, displayHeight));
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
