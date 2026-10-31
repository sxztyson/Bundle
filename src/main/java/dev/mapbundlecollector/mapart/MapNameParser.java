package dev.mapbundlecollector.mapart;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Parses raw filled-map display names into (project, row, col). Pure Java so the rules are
// unit-testable and reusable without any Minecraft or GUI dependency.
public final class MapNameParser {
    // Matches names like: HSiRN5MaQAEEEvk_3_3.nbt, HSiRN5MaQAEEEvk_0_1, MyMap-2-4, Art 1 2,
    // Sam [0, 0], map.2.4 (dots are also coordinate separators; a trailing dot-suffix that
    // is not followed by another number, like ".nbt", still parses as a file extension).
    private static final Pattern PATTERN_GRID = Pattern.compile("^(.+?)[_\\-\\s,.\\[\\(]+(\\d+)[_\\-\\s,.xX]+(\\d+)[_\\]\\)]*(?:\\.[a-zA-Z0-9]+)?$");
    // Fallback 1D index: MyMap_1.nbt, MyMap 4, Art #2, MyMap.4
    private static final Pattern PATTERN_1D = Pattern.compile("^(.+?)[_\\-\\s.#\\[\\(]+(\\d+)[_\\]\\)]*(?:\\.[a-zA-Z0-9]+)?$");
    // Spreadsheet-style grid: Winter 5x5 A1 .. E5, Art B3, Collage c2, Art.B3. The letter
    // is the ROW (A = first row), the 1-based number is the COLUMN - the reading order of
    // stamp-style mapart pieces.
    private static final Pattern PATTERN_LETTER_GRID = Pattern.compile("^(.+?)[_\\-\\s,.]+([A-Za-z])[ _]?(\\d{1,2})[_\\]\\)]*(?:\\.[a-zA-Z0-9]+)?$");

    public record ParsedTile(String projectId, int row, int col, String rawName) {}

    private MapNameParser() {}

    // Returns null for null/blank names; grid names parse to (row, col) honouring xyFirst,
    // letter-grid names to (letter row, 1-based column - 1), 1D indices map to (index, 0),
    // plain names fall back to (0, 0).
    public static ParsedTile parse(String rawName, boolean xyFirst) {
        if (rawName == null) return null;
        String name = rawName.trim();
        if (name.isEmpty()) return null;

        Matcher gridMatcher = PATTERN_GRID.matcher(name);
        if (gridMatcher.matches()) {
            String project = gridMatcher.group(1).trim();
            int num1 = Integer.parseInt(gridMatcher.group(2));
            int num2 = Integer.parseInt(gridMatcher.group(3));
            int col = xyFirst ? num1 : num2;
            int row = xyFirst ? num2 : num1;
            return new ParsedTile(project, row, col, name);
        }

        Matcher singleMatcher = PATTERN_1D.matcher(name);
        if (singleMatcher.matches()) {
            String project = singleMatcher.group(1).trim();
            int index = Integer.parseInt(singleMatcher.group(2));
            return new ParsedTile(project, index, 0, name);
        }

        Matcher letterMatcher = PATTERN_LETTER_GRID.matcher(name);
        if (letterMatcher.matches()) {
            int row = Character.toUpperCase(letterMatcher.group(2).charAt(0)) - 'A';
            int col = Integer.parseInt(letterMatcher.group(3)) - 1;
            if (row >= 0 && row < 26 && col >= 0) {
                return new ParsedTile(letterMatcher.group(1).trim(), row, col, name);
            }
        }

        // Standalone map without a numeric index
        return new ParsedTile(name, 0, 0, name);
    }
}
