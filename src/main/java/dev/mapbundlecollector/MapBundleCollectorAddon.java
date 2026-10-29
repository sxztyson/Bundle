package dev.mapbundlecollector;

import dev.mapbundlecollector.modules.MapArtViewerModule;
import dev.mapbundlecollector.modules.MapBundleCollector;
import dev.mapbundlecollector.modules.MapChestSorter;
import dev.mapbundlecollector.modules.MapDuplicateExtractor;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.world.item.Items;

public final class MapBundleCollectorAddon extends MeteorAddon {
    public static final Category CATEGORY = new Category("Bundle Sorter", () -> Items.BUNDLE.getDefaultInstance());

    @Override
    public void onInitialize() {
        Modules.get().add(new MapBundleCollector());
        Modules.get().add(new MapChestSorter());
        Modules.get().add(new MapDuplicateExtractor());
        Modules.get().add(new MapArtViewerModule());
    }

    @Override
    public void onRegisterCategories() {
        Modules.registerCategory(CATEGORY);
    }

    @Override
    public String getPackage() {
        return "dev.mapbundlecollector";
    }
}
