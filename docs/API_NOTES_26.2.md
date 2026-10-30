# Verified API cheat-sheet — MC 26.2 (Fabric Loom, Meteor 26.2-SNAPSHOT, Java 25)

Every signature below was verified with `javap` / `unzip -l` against the actual jars
(parameter names come from the `LocalVariableTable`/`MethodParameters` debug attrs
present in the deobf jar). Items marked **PROVEN in repo** additionally appear in
`src/main/java` and therefore compile.

## Verification sources

| What | Path |
|---|---|
| Mapped (Mojang-name) MC 26.2 jar | `C:\Users\ComicSquid\.gradle\caches\fabric-loom\minecraftMaven\net\minecraft\minecraft-merged-deobf\26.2\minecraft-merged-deobf-26.2.jar` (abbreviated **[MC]** below) |
| Raw processed MC jars (same class names) | `C:\Users\ComicSquid\.gradle\caches\fabric-loom\26.2\minecraft-merged.jar` etc. |
| Meteor 26.2 binary | `C:\Users\ComicSquid\.gradle\caches\modules-2\files-2.1\meteordevelopment\meteor-client\26.2-SNAPSHOT\b55524ae9c5d79df9e854ab04753612dd8abe36b\meteor-client-26.2-SNAPSHOT.jar` (**[METEOR]**) |
| Meteor 26.2 sources | `...\meteor-client\26.2-SNAPSHOT\78fd6e89694eda4e009dc48aa2c0481def809dd7\meteor-client-26.2-SNAPSHOT-sources.jar` (**[METEOR-SRC]**) |
| fabric-loader 0.19.3 | `C:\Users\ComicSquid\.gradle\caches\modules-2\files-2.1\net.fabricmc\fabric-loader\0.19.3\354dfaa02d0552e11867f85dff7cdbfaf813ba3e\fabric-loader-0.19.3.jar` (**[LOADER]**) |

JDK used: `C:\Users\ComicSquid\.gradle\jdks\temurin-25\bin` (javap/unzip).

---

## 1. `com.mojang.blaze3d.platform.NativeImage` — [MC], javap

`public final class NativeImage implements AutoCloseable`

Constructors (all):
```java
public NativeImage(int width, int height, boolean calloc)   // format = RGBA; useCalloc flag
public NativeImage(NativeImage$Format format, int width, int height, boolean calloc)
public NativeImage(NativeImage$Format format, int width, int height, boolean calloc, long pointer)
```
(The 3-arg ctor delegates with `Format.RGBA` — bytecode-confirmed. `setPixel` REQUIRES the
RGBA format, it throws `IllegalArgumentException("setPixelRGBA only works on RGBA images")` otherwise.)

Pixel access / info:
```java
public int  getPixel(int x, int y)          // returns ARGB (converts internal ABGR via ARGB.fromABGR)
public void setPixel(int x, int y, int argb) // ARGB in; converts to ABGR internally
public void setPixelABGR(int x, int y, int abgr)
public int[] getPixels() / getPixelsABGR()  // full-image copies
public int  getWidth();  public int getHeight();
public NativeImage$Format format();
public void close();      // frees off-heap buffer; IDEMPOTENT (zeroes pointer, second call no-op)
public boolean isClosed();
public void untrack(); public long getPointer(); public ByteBuffer getPixelBytes();
```
Fill/copy (param names from LocalVariableTable):
```java
public void fillRect(int xs, int ys, int width, int height, int pixel)   // ARGB pixel
public void copyFrom(NativeImage other)                                  // full copy, same size required
public void copyRect(int startX, int startY, int offsetX, int offsetY, int sizeX, int sizeY, boolean swapX, boolean swapY)
public void copyRect(NativeImage other, int startX, int startY, int offsetX, int offsetY, int sizeX, int sizeY, boolean swapX, boolean swapY)
public void resizeSubRectTo(int sourceX, int sourceY, int sizeX, int sizeY, NativeImage to) // STB-scales a sub-rect into `to` — handy for the 128x128 mini tier
public NativeImage mappedCopy(IntUnaryOperator mapper)
```
Static readers (`read(InputStream)`, `read(ByteBuffer)`, ...), `writeToFile(File/Path)` also exist.

Bounds/format errors throw `IllegalArgumentException` (bytecode: `isOutsideBounds` check in
`setPixelABGR`). No thread checks anywhere in the class — see item 15.

**How to use:** `new NativeImage(w, h, false)` then `setPixel(x, y, 0xAARRGGBB)`; PROVEN in repo
(`MapArtManager.rebuildTexture`).

---

## 2. `net.minecraft.client.renderer.texture.DynamicTexture` — [MC], javap + javap -c

`public class DynamicTexture extends AbstractTexture implements Dumpable`

```java
public DynamicTexture(java.util.function.Supplier<String> label, NativeImage pixels)
public DynamicTexture(String label, int width, int height, boolean calloc)
public DynamicTexture(java.util.function.Supplier<String> label, int width, int height, boolean calloc)
public void upload()                       // full-image upload: RenderSystem.getDevice().createCommandEncoder().writeToTexture(texture, pixels)
public NativeImage getPixels()
public void setPixels(NativeImage pixels)  // CLOSES the old pixels, stores new one (no upload — call upload() after)
public void close()                        // closes pixels + super.close() (frees GpuTexture/view/sampler; idempotent)
```

**Constructor bytecode facts (javap -c):**
- `(Supplier, NativeImage)` — stores pixels, calls `createTexture(Supplier)` **then calls `upload()` immediately**. So the pixels go to the GPU inside the constructor.
- `(String,int,int,boolean)` and `(Supplier,int,int,boolean)` — allocate the NativeImage and call `createTexture(...)` (GPU texture object is created) but do **NOT** upload.
- `createTexture(...)` calls `RenderSystem.getDevice()` → `GpuDevice.createTexture(label, usage=5, GpuFormat.RGBA8_UNORM, w, h, 1, 1)`, then takes a sampler from `RenderSystem.getSamplerCache().getRepeat(FilterMode.NEAREST)` (**NEAREST filtering, repeat addressing**) and creates a texture view.

**There is no partial-upload method on DynamicTexture.** `upload()` always writes the whole
image. A lower-level partial path exists (render thread only):
```java
// com.mojang.blaze3d.systems.CommandEncoder (verified):
public void writeToTexture(GpuTexture destination, NativeImage source)                                  // full
public void writeToTexture(GpuTexture destination, NativeImage source, int mipLevel, int depthOrLayer, int destX, int destY) // whole `source` copied at dest offset
public void writeToTexture(GpuTexture destination, ByteBuffer source, int mipLevel, int depthOrLayer, int destX, int destY, int width, int height)
// reachable as: RenderSystem.getDevice().createCommandEncoder().writeToTexture(dt.getTexture(), img, 0, 0, x, y);
```

**How to use:** `new DynamicTexture(() -> "chunk_r_c", image)` PROVEN in repo — but it must
run on the render thread (see item 15).

---

## 3. `net.minecraft.client.renderer.texture.TextureManager` — [MC], javap -c

```java
public void register(net.minecraft.resources.Identifier id, net.minecraft.client.renderer.texture.AbstractTexture texture)
public void release(net.minecraft.resources.Identifier id)     // single-arg, NO flags/booleans in 26.2
public AbstractTexture getTexture(net.minecraft.resources.Identifier id)
public void registerAndLoad(Identifier, ReloadableTexture)
public void registerForNextReload(Identifier)
public void tick(); public void close();
```

**Bytecode semantics (important):**
- `register(id, tex)` = `byPath.put(...)`; if an existing *different* texture was registered
  under `id` it is `safeClose`d (closed inside try/catch). → **Re-registering the same id with a
  new DynamicTexture automatically closes the old one** — a plain `register()` call is a valid
  replacement strategy.
- `release(id)` = `byPath.remove(id)` + `safeClose(removed)`. Combined with a manual
  `texture.close()` first (what the repo does) it is a *double close* — verified harmless:
  `AbstractTexture.close()` nulls its fields; `NativeImage.close()` zeroes its pointer.
- `getTexture(id)` for an **unregistered** id does NOT return null — it creates a
  `SimpleTexture(id)`, calls `registerAndLoad` (async resource-pack load, ends as the
  magenta/black missing texture) and registers it under that id. → **Never blit a chunk id
  that isn't registered yet**: each first blit would trigger a resource load and occupy the id
  (a later real `register()` does clean it up, but avoid the churn). Track a `registered` set.

**How to use:** `mc.getTextureManager().register(textureId, dynamicTexture)` /
`.release(textureId)` — PROVEN in repo.

---

## 4. `net.minecraft.resources.Identifier` — [MC], javap -c

```java
public static Identifier fromNamespaceAndPath(String namespace, String path)
public static Identifier parse(String) / tryParse(String) / withDefaultNamespace(String) / tryBuild(String, String)
public static boolean isValidPath(String) / isValidNamespace(String) / validPathChar(char) / isAllowedInIdentifier(char)
public String getPath(); public String getNamespace(); public String toShortString(); ...
```

`fromNamespaceAndPath` → `createUntrusted` → `assertValidNamespace/assertValidPath`, which
**throw `net.minecraft.IdentifierException` (unchecked) on invalid input** (bytecode-confirmed;
the private 2-arg ctor itself only uses `assert`).

**Allowed path chars (decoded from `validPathChar` bytecode):** `a-z`, `0-9`, `_`, `-`, `.`, `/`.
NO uppercase, NO spaces, NO other punctuation. So `"mapbundlecollector", "chunk/proj_0_1"` is
VALID (slash + digits + underscore are all legal). Note the repo already sanitizes project ids
with `replaceAll("[^a-z0-9_.-]", "_").toLowerCase()` — keep doing that for path segments.

**How to use:** PROVEN in repo (`Identifier.fromNamespaceAndPath("mapbundlecollector", ...)`).

---

## 5. `net.minecraft.client.gui.GuiGraphicsExtractor` — [MC], javap -v (param names from LVT)

Class is `public class GuiGraphicsExtractor` (56 KB, has ScissorStack inner class). Screens
render through `Screen.extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta)`.

### blit overload list (ALL, verified)

| # | Signature (names from bytecode) | Meaning |
|---|---|---|
| 1 | `blit(RenderPipeline, Identifier texture, int x, int y, float u, float v, int width, int height, int textureWidth, int textureHeight, int color)` | Draw `width x height` px at `(x,y)` from sub-rect `(u,v)`-`(u+width, v+height)`; region size == draw size; `color` tint (0xFFFFFFFF / -1 for none) |
| 2 | `blit(RenderPipeline, Identifier, int x, int y, float u, float v, int width, int height, int textureWidth, int textureHeight)` | same, color = -1 |
| 3 | **`blit(RenderPipeline, Identifier, int x, int y, float u, float v, int width, int height, int srcWidth, int srcHeight, int textureWidth, int textureHeight)`** | **SUB-REGION + SCALE**: source region `(u,v)` + `(srcWidth x srcHeight)` px, drawn into a `width x height` screen rect. UVs are `u/textureWidth`, `(u+srcWidth)/textureWidth`, etc. (bytecode-confirmed). color = -1 |
| 4 | `blit(RenderPipeline, Identifier, int x, int y, float u, float v, int width, int height, int srcWidth, int srcHeight, int textureWidth, int textureHeight, int color)` | master overload everything delegates to; color tint |
| 5 | `blit(Identifier location, int x0, int y0, int x1, int y1, float u0, float u1, float v0, float v1)` | corner rect + **already-normalized** UVs (0..1); uses `RenderPipelines.GUI_TEXTURED` internally |
| 6 | `blit(GpuTextureView textureView, GpuSampler sampler, int x0, int y0, int x1, int y1, float u0, float u1, float v0, float v1)` | direct GPU-view variant (can draw an unregistered `DynamicTexture.getTextureView()` + `getSampler()`), normalized UVs |
| 7 | `blitSprite(RenderPipeline, Identifier sprite, int x, int y, int w, int h)` (+ `float alpha`, + `int color` variants) | sprite-atlas helper |
| 8 | `blitSprite(RenderPipeline, Identifier, x, y, w, h, u, v, uW, uH, texW, texH)` / `(+ color)` | 8/9-int sprite sub-rect variants |
| 9 | `blitSprite(RenderPipeline, TextureAtlasSprite, int x, int y, int w, int h)` (+ color) | atlas-sprite variant |

**Answer to the key question: YES — you can blit PART of a texture.** Overload #3 does
exactly `u,v + srcWidth,srcHeight -> width,height rect` (source region ≠ draw size). The repo
currently uses #3 with `u=v=0`, `srcWidth=texW`, `srcHeight=texH` (whole-texture scaling).

All Identifier-based overloads resolve the texture via
`Minecraft.getTextureManager().getTexture(id)` → `AbstractTexture.getTextureView()/getSampler()`
(bytecode of `innerBlit`), so the id must be registered (see item 3 gotcha).

### Other verified members used by the repo

```java
public void fill(int x0, int y0, int x1, int y1, int col)            // CORNERS
public void fill(RenderPipeline, int x0, int y0, int x1, int y1, int col)
public void fillGradient(int x0, int y0, int x1, int y1, int colFrom, int colTo)
public void outline(int x, int y, int width, int height, int color)   // TOP-LEFT + SIZE (not corners!)
public void text(Font font, String str, int x, int y, int color)
public void text(Font, String, int, int, int, boolean dropShadow)
public void text(Font, Component, int, int, int [, boolean])
public void text(Font, FormattedCharSequence, int, int, int [, boolean])
public void textWithWordWrap(Font, FormattedText, int x, int y, int wrapWidth, int color [, boolean])
public void textWithBackdrop(Font, Component, int x, int y, int w, int h)
public void centeredText(Font font, String str, int x, int y, int color)      // x = CENTER x
public void centeredText(Font, Component, int, int, int)
public void centeredText(Font, FormattedCharSequence, int, int, int)
public void enableScissor(int x0, int y0, int x1, int y1)                     // corners
public void disableScissor()
public boolean containsPointInScissor(int x, int y)
public void setComponentTooltipForNextFrame(Font font, List<Component> lines, int x, int y)
public void setComponentTooltipForNextFrame(Font, List<Component>, int, int, Identifier textureOverride)
public void setTooltipForNextFrame(...)   // many overloads (Component / List<FormattedCharSequence> / ItemStack ...)
public void textHighlight(int x0, int y0, int x1, int y1, boolean)
public ActiveTextCollector textRenderer()  // + 3 more variants (widget text rendering)
```
Colors are ARGB ints (`0xAARRGGBB`). All of the above are PROVEN in repo except
`containsPointInScissor`/`textHighlight`/`textWithWordWrap`.

---

## 6. `net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED` — [MC], javap -c on <clinit>

Exists: `public static final com.mojang.blaze3d.pipeline.RenderPipeline GUI_TEXTURED` — PROVEN in repo.

GUI-family pipelines (all `public static final RenderPipeline`):
`GUI`, `GUI_INVERT`, `GUI_TEXT`, `GUI_TEXT_GRAYSCALE`, `GUI_TEXT_HIGHLIGHT`,
`GUI_TEXTURED`, `GUI_TEXTURED_PREMULTIPLIED_ALPHA`, `GUI_OPAQUE_TEXTURED_BACKGROUND`,
`GUI_NAUSEA_OVERLAY`.

`GUI_TEXTURED` bytecode construction: built from `GUI_TEXTURED_SNIPPET` which uses
vertex/fragment shaders `core/position_tex_color`, vertex format `POSITION_TEX_COLOR`,
QUADS, bind layouts `MATRICES_PROJECTION` + `SAMPLER0`, blend = `BlendFunction.TRANSLUCENT`
(straight alpha), location `pipeline/gui_textured`. `GUI_TEXTURED_PREMULTIPLIED_ALPHA` is the
same but premultiplied-alpha blending — use it only if your pixels are premultiplied
(NativeImage setPixel pixels are straight alpha, so stick with `GUI_TEXTURED`).

**Texture-size constraints: NONE encoded anywhere** — sampling is normalized-UV (no 256px
assumption, no POT requirement, no size check in `innerBlit`). The only limit is the GPU's
`DeviceLimits.maxTextureSize()` (see item 10). Filtering comes from the texture's own sampler
(DynamicTexture = NEAREST — see item 2).

---

## 7. `net.minecraft.client.Minecraft` — [MC], javap

```java
public static Minecraft getInstance()                       // PROVEN in repo
public TextureManager getTextureManager()                   // PROVEN in repo
public final Font font;                                     // PROVEN (mc.font)
public final Gui gui;                                       // PROVEN (mc.gui.setScreen, mc.gui.hud)
// net.minecraft.client.gui.Gui:
public final net.minecraft.client.gui.Hud hud;
// net.minecraft.client.gui.Hud:
public ChatComponent getChat();
```

**Scheduling onto the render/client thread:** `Minecraft extends
ReentrantBlockableEventLoop<Runnable>` → `BlockableEventLoop<R> implements
java.util.concurrent.Executor`. So `Minecraft.getInstance().execute(Runnable)` EXISTS
(inherited). Full inherited API (verified on `net.minecraft.util.thread.BlockableEventLoop`):
```java
public void execute(Runnable)                      // enqueue; runs on the client thread during the frame loop
public void executeIfPossible(Runnable)
public void executeBlocking(Runnable)              // blocks caller until run (careful: deadlock if called from client thread)
public CompletableFuture<Void> submit(Runnable)
public <V> CompletableFuture<V> submit(Supplier<V>)
public void schedule(Runnable)                     // from TaskScheduler
public boolean isSameThread()
public int getPendingTasksCount()
```
(The yarn name `tell` does not exist in Mojang mappings; `execute` is the method.)
Thread predicates: `com.mojang.blaze3d.systems.RenderSystem.isOnRenderThread()` /
`assertOnRenderThread()` (verified).

**How to use:** from a background chunk-build thread call
`Minecraft.getInstance().execute(() -> { register/upload here })`.

---

## 8. Map data — [MC], javap

```java
// net.minecraft.world.level.saveddata.maps.MapItemSavedData
public byte[] colors;                 // PUBLIC non-final field, 128*128 = 16384 entries — PROVEN in repo (tile.getColors reads it)
// (class also exposes centerX/centerZ/scale/locked finals — irrelevant here)

// net.minecraft.world.level.material.MapColor
public static int getColorFromPackedId(int packedId)   // byte & 0xFF -> ARGB — PROVEN in repo
```
(Do not confuse with `net.minecraft.client.color.item.MapColor` — an unrelated record for item tinting.)

---

## 9. `net.fabricmc.loader.api.FabricLoader` — [LOADER], javap

```java
public static FabricLoader getInstance()
public abstract java.nio.file.Path getGameDir()      // returns Path
public abstract java.io.File getGameDirectory()      // legacy File variant
```
`fabric-loader 0.19.3` is an `implementation` dependency (build.gradle.kts) — on the compile
classpath. Meteor itself uses `FabricLoader.getInstance().getGameDir().resolve(...)` in
`MeteorClient` ([METEOR-SRC] line 62), so the pattern is doubly proven.

---

## 10. GPU max texture size — [MC], javap

There is NO `RenderSystem.maxTextureSize()` / `GlStateManager` query in 26.2 (GlStateManager
class has no such method; grepped). The verified chain is:
```java
com.mojang.blaze3d.systems.RenderSystem.getDevice()          // static; GpuDevice
  -> public DeviceInfo getDeviceInfo()                        // on GpuDevice
  -> public DeviceLimits limits()                             // record accessor on DeviceInfo
  // com.mojang.blaze3d.systems.DeviceLimits:
  public int maxTextureSize()                                 // GL_MAX_TEXTURE_SIZE equivalent
  public int maxTextureSizeForFormat(com.mojang.blaze3d.GpuFormat)
  public int maxAnisotropy(); public int minUniformOffsetAlignment();
  public long maxMemoryAllocationSize(); public int maxMultiDrawDirectInterleavedDrawCount(); public int maxColorAttachments();
```
One-liner: `RenderSystem.getDevice().getDeviceInfo().limits().maxTextureSize()`.
Render thread only (touches the device). Informational for us: 2048x2048 chunks are far below
any GPU limit (even 16384).

---

## 11. Meteor Client 26.2 — [METEOR] javap + [METEOR-SRC]

Settings builders (all verified signatures):
```java
// meteordevelopment.meteorclient.settings.IntSetting$Builder
public Builder min(int); public Builder max(int); public Builder range(int, int)
public Builder sliderMin(int); public Builder sliderMax(int); public Builder sliderRange(int, int)
public Builder noSlider(); public IntSetting build()
// meteordevelopment.meteorclient.settings.Setting$SettingBuilder (base of all builders)
public B name(String); public B description(String); public B defaultValue(V)
// BoolSetting$Builder: ctor + build() only (name/description/defaultValue from base)
// EnumSetting$Builder<T extends Enum<?>>: ctor + build() only
// meteordevelopment.meteorclient.settings.SettingGroup
public <T extends Setting<?>> T add(T setting)      // returns the setting — PROVEN
// Module base: settings.getDefaultGroup() — PROVEN; Setting<T>.get()/set() — PROVEN
```
**Can `.min(0)` coexist with `.sliderRange(0, 1024)`?** YES — they are independent fields
([METEOR-SRC] `IntSetting`): `min`/`max` are *value validation* (`isValueValid`), while
`sliderMin`/`sliderMax` only bound the GUI slider. Defaults: `min=Integer.MIN_VALUE`,
`max=Integer.MAX_VALUE`, `sliderMin=0`, `sliderMax=10` — **so always set `sliderRange` (or
`sliderMax`) for values > 10 or the slider will cap at 10.** Repo-proven combos:
`.min(50).sliderRange(50,180)` (MapArtViewerModule), `.min(0).sliderMax(2000)`,
`.min(20).sliderMax(1000)` (MapBundleCollector).

`meteordevelopment.meteorclient.events.world.TickEvent.Post` — verified ([METEOR-SRC]):
```java
public class TickEvent { public static class Pre extends TickEvent {...} public static class Post extends TickEvent {...} }
```
`@EventHandler private void onTick(TickEvent.Post event)` — PROVEN in 4 repo modules (fires
each client tick on the client thread).

Chat: `mc.gui.hud.getChat().addClientSystemMessage(Component)` — every link verified
(`Minecraft.gui` -> `Gui.hud` public field -> `Hud.getChat()` -> `ChatComponent.addClientSystemMessage(Component)`);
PROVEN in repo.

---

## 12. `meteordevelopment.meteorclient.addons.MeteorAddon` lifecycle — [METEOR]/[METEOR-SRC]

```java
public abstract class MeteorAddon {
    public String name; public String[] authors;   // injected from fabric.mod.json
    public abstract void onInitialize();
    public void onRegisterCategories();
    public abstract String getPackage();
    ...
}
```
Timing ([METEOR-SRC] `MeteorClient.onInitializeClient`, lines ~81-132): addon `onInitialize()`
is invoked from Meteor's Fabric **client entrypoint**, i.e. during early client bootstrap on
the main/render thread, AFTER Meteor's systems are constructed but BEFORE configs load and
long before the title screen. `Minecraft.getInstance()` is already non-null there (Meteor
assigns `mc = Minecraft.getInstance()` a few lines earlier).

- **Safe:** file IO, creating directories, starting executors, registering modules (current
  repo usage proves the module part; Meteor itself does `mkdirs()` at the same point).
- **NOT safe:** anything touching `RenderSystem`/textures/`DynamicTexture` — no window/GL
  device lifetime is guaranteed yet; defer those to `mc.execute(...)` or first screen render.
- UNVERIFIED nuance: whether resource packs are loaded at entrypoint time (irrelevant to us —
  we only do disk IO).

---

## 13. GSON availability

MC 26.2 declares `com.google.code.gson:gson:2.14.0` as a runtime library
(`C:\Users\ComicSquid\.gradle\caches\fabric-loom\26.2\mojang_minecraft_info.json`,
grep: `com.google.code.gson:gson:2.14.0`; jar at
`C:\Users\ComicSquid\.gradle\caches\modules-2\files-2.1\com.google.code.gson\gson\2.14.0\...`).
MC client classes reference `com/google/gson` directly (e.g. `net.minecraft.client.Options$*`),
so loom puts it on the compile classpath too. fabric-loader bundles a *relocated* copy
(`net.fabricmc.loader.impl.lib.gson`) — no conflict. Informational: the cache engine stays
hand-rolled JSON; UI code may `import com.google.gson.*` freely.

---

## 14. `HandledScreenMixin` — how MapArtSideWidget attaches (source: `src/main/java/dev/mapbundlecollector/mixin/HandledScreenMixin.java`)

1. Mixes into `AbstractContainerScreen` (extends `Screen` to shadow `leftPos/topPos/imageWidth/imageHeight`).
2. `@Inject(method = "init", at = @At("TAIL"))` — refreshes `MapArtManager`, creates one
   `MapArtSideWidget`, calls `updateBounds(leftPos, topPos, imageWidth, imageHeight)` and
   `addRenderableWidget(widget)` so the container screen renders + routes events to it.
3. `@Inject(method = "extractRenderState", at = @At("HEAD"))` — re-syncs widget bounds every
   frame (recipe-book resizes etc.).
4. `@Inject(HEAD, cancellable)` on `keyPressed / mouseScrolled / mouseDragged / mouseClicked /
   mouseReleased` — forwards each event to the widget first; `cir.setReturnValue(true)` when consumed.
5. The widget itself implements `Renderable, GuiEventListener, NarratableEntry` and draws the
   map preview with the same `GuiGraphicsExtractor` API the render screen uses (including its
   own `enableScissor`/`disableScissor` around the preview blit).

---

## 15. Thread-safety / construction rules (bytecode-verified)

- **NativeImage**: plain off-heap buffer, no synchronization, no thread checks. Building one
  on a background thread is fine — as long as only ONE thread touches it at a time and nobody
  writes after `close()` (post-close writes throw `IllegalArgumentException` via the 0x0-size
  bounds check).
- **DynamicTexture**: **must be constructed on the render thread.** Every constructor calls
  `createTexture(...)` which calls `RenderSystem.getDevice()`; the `(Supplier, NativeImage)`
  ctor additionally calls `upload()` immediately (GPU write via the command encoder). The
  repo's current code happens to be safe only because `rebuildTexture` is reached from tick
  (client thread). For the async chunk builder: **fill `NativeImage` on the worker, then do
  `new DynamicTexture(label, image)` + `textureManager.register(id, dt)` inside
  `Minecraft.getInstance().execute(...)`**.
- `DynamicTexture.upload()` and `TextureManager.register/release/getTexture` likewise touch
  GPU state / the render-thread texture map — render thread only. `MapItemSavedData.colors`
  is a plain array; background reads are lock-free (spec already accepts stale reads).

---

## RECOMMENDED RENDERER PATTERN (verified APIs only)

```
worker thread (max 2)                     render thread (mc.execute)
---------------------                     ---------------------------
chunk build requested
  -> resolve tile colors:
     live mapState.colors (racy-read ok)
     else cache file, else placeholder
  -> NativeImage img = new NativeImage(w, h, false)   // w,h <= 2048; edge chunks smaller
     per-pixel img.setPixel(x, y, MapColor.getColorFromPackedId(colors[i] & 0xFF))
     (or fillRect for flat placeholders; resizeSubRectTo for the 128x128 mini tier)
  -> Minecraft.getInstance().execute(() -> {
         DynamicTexture dt = new DynamicTexture(() -> "mbc_chunk_r"+r+"_c"+c, img); // uploads NOW
         Identifier id = Identifier.fromNamespaceAndPath("mapbundlecollector",
             "map_art_" + safeProject + "/chunk_" + r + "_" + c);   // '/' legal in paths
         Minecraft.getInstance().getTextureManager().register(id, dt);  // replaces+closes old id registration
         markChunkReady(r, c, id);
     });
```

Draw loop (`GuiGraphicsExtractor ctx`), per visible chunk (viewport culling):
- chunk not ready -> `ctx.fill(x0, y0, x1, y1, 0xFF1E2128)` placeholder; **do not blit an
  unregistered id** (getTexture would spawn+register a resource-loading SimpleTexture).
- ready -> whole-chunk blit with scaling:
  `ctx.blit(RenderPipelines.GUI_TEXTURED, id, dx, dy, 0f, 0f, drawW, drawH, srcW, srcH, srcW, srcH);`
  (overload #3; `srcW/srcH` = chunk pixel dims — smaller for edge chunks; `drawW/H` = zoomed rect).
- Sub-region of a chunk texture (e.g. panning inside one chunk, or a zoom-in that needs only
  part): same overload with `u/v` + `srcWidth/srcHeight` = the visible crop, `width/height` =
  its screen rect. There is no need to register per-crop textures.
- Mini tier (zoomed far out): register a separate 128x128 DynamicTexture per chunk (built
  with stride-16 sampling or `resizeSubRectTo`), NOT a downscale-blit of the full texture —
  DynamicTexture's sampler is **NEAREST**, so drawing a 2048px texture at ~15px aliases badly.
- Clamp inside `enableScissor(x0,y0,x1,y1)` / `disableScissor()` for the side widget preview
  (PROVEN pattern in MapArtSideWidget).
- Eviction (LRU): on the render thread `textureManager.release(id)` — removes registration
  AND closes the texture (single call is enough; `close()` then `release()` also works, both
  are idempotent). The SAME Identifier can be re-used after release+re-register — `register()`
  simply `put`s again (and closes whatever occupied the id).
- Identifier reuse: ids are pure map keys; after `release(id)` nothing remembers the id. Only
  constraint is the path charset (item 4). Keep ids stable per chunk (`chunk_r_c`) and re-register
  to refresh content.
- Single-tile updates: either rebuild the chunk image on a worker and re-register, or
  (advanced, render thread) `RenderSystem.getDevice().createCommandEncoder().writeToTexture(
  dt.getTexture(), smallImg, 0, 0, destX, destY)` — DynamicTexture itself has no partial upload.
- GPU limit check (optional, once, render thread):
  `int max = RenderSystem.getDevice().getDeviceInfo().limits().maxTextureSize();` (2048 is always safe).

## UNVERIFIED / caveats

- **Fabric entrypoint window/GL state** (item 12): inferred from [METEOR-SRC] call order; not
  run-verified that no GL context exists at `onInitialize` — but no API we call there needs one.
- **`blit(GpuTextureView, GpuSampler, ...)` (overload #6) path** was signature-verified but not
  exercised in this repo; the registered-`Identifier` path is the proven one. Best guess: it
  works identically and skips the TextureManager lookup (useful if you want zero registry churn).
- **Meteor config-save timing of new settings**: new `IntSetting`s on existing modules load
  with defaults on first run (verified from `IntSetting.load` source using `getIntOr`), but the
  migration behavior when the user later shrinks `max` below a saved value is UNVERIFIED —
  best guess: `isValueValid` only gates UI input, saved out-of-range values load as-is.
- `DeviceLimits` record canonical-constructor argument order was not decoded (irrelevant —
  only accessors are needed).
