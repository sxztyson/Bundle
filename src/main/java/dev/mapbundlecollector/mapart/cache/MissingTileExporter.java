package dev.mapbundlecollector.mapart.cache;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

// Writes the missing-tiles report as <outDir>/<safeProjectId>.json. rows/cols are the resolved
// canvas dims. Pure Java, manual JSON writing with proper escaping.
public final class MissingTileExporter {
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private MissingTileExporter() {}

    public static Path exportJson(Path outDir, String projectId, int rows, int cols,
        List<int[]> missingTiles, int presentCount) throws IOException {
        List<int[]> missing = missingTiles == null ? List.of() : missingTiles;
        StringBuilder sb = new StringBuilder(256 + missing.size() * 32);
        sb.append("{\n");
        sb.append("  \"project\": ");
        Json.escape(sb, projectId);
        sb.append(",\n");
        sb.append("  \"expectedColumns\": ").append(cols).append(",\n");
        sb.append("  \"expectedRows\": ").append(rows).append(",\n");
        sb.append("  \"expectedTotal\": ").append((long) rows * cols).append(",\n");
        sb.append("  \"presentTiles\": ").append(presentCount).append(",\n");
        sb.append("  \"missingCount\": ").append(missing.size()).append(",\n");
        sb.append("  \"generatedAt\": \"").append(LocalDateTime.now().format(TIMESTAMP)).append("\",\n");
        sb.append("  \"missingTiles\": [");
        if (!missing.isEmpty()) {
            sb.append('\n');
            for (int i = 0; i < missing.size(); i++) {
                int[] tile = missing.get(i);
                sb.append("    { \"row\": ").append(tile[0]).append(", \"col\": ").append(tile[1]).append(" }");
                if (i < missing.size() - 1) sb.append(',');
                sb.append('\n');
            }
            sb.append("  ]\n");
        } else {
            sb.append("]\n");
        }
        sb.append("}\n");
        Files.createDirectories(outDir);
        Path out = outDir.resolve(MapArtCache.safeProjectId(projectId) + ".json");
        Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
        return out;
    }
}
