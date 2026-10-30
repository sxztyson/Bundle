package dev.mapbundlecollector.mapart.cache;

// Reverse-index location of one cached tile (a tile whose colors exist on disk as
// tiles/r_c.bin). Returned by MapArtCache.findByMapId; immutable and thread-safe by design.
public record TileLocation(String projectId, int row, int col) {}
