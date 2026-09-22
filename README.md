# Bundle - Map Bundle Collector & Map Art Viewer
**Made by tysonsxz**

A privacy-focused Meteor Client addon for Fabric / Minecraft 1.21.11.

---

## 📷 Screenshots

### In-Game Inventory Side Widget
Live stitched map preview right next to your inventory or container with piece counts, zoom controls, and loaded toggle:

![Inventory Side Widget](share/screenshots/inventory_preview.png)

### Fullscreen Map Art Render Screen
Interactive full-screen viewer with zoom, panning, centering, grid toggling, flip orientation, and clear memory options:

![Fullscreen Map Render](share/screenshots/fullscreen_render.png)

---

## ✨ Features
- **Map Bundle Collector**: Automatically moves matching filled maps into inventory bundles when you manually open a container.
- **Map Art Viewer**: Previews multi-part map art directly from bundles in your inventory with side-panel preview and missing tile detection.
- **Privacy & Safety**: Client-only operation with configurable action and open delays.

---

## ⚙️ Requirements
- **Minecraft**: `1.21.11`
- **Fabric Loader**: `>= 0.18.2`
- **Meteor Client**: for 1.21.11
- **Java**: 21+

---

## 🚀 How to Install
1. Make sure you have **Fabric Loader** and **Meteor Client** installed for Minecraft 1.21.11.
2. Press `Win + R`, type `%appdata%\.minecraft\mods` and hit Enter.
3. Place `map-bundle-collector-1.0.0.jar` (found in the [`share/`](share/) folder or `build/libs/`) into your `mods` folder.
4. Launch Minecraft!

---

## 🛠️ Building from Source
Build with Gradle:
```bash
./gradlew build
```
The remapped addon JAR is written to `build/libs/`.
