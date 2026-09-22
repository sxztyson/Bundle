package dev.mapbundlecollector;

import dev.mapbundlecollector.modules.MapArtViewerModule;
import dev.mapbundlecollector.modules.MapBundleCollector;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.item.Items;

public final class MapBundleCollectorAddon extends MeteorAddon {
    public static final Category CATEGORY = new Category("Map Collector", Items.BUNDLE.getDefaultStack());

    @Override
    public void onInitialize() {
        Modules.get().add(new MapBundleCollector());
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
