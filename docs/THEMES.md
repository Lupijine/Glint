# Glint glass themes

A theme supplies the artwork behind the glass and its palette. Three themes are pictures that
ship with the app: Chrome, Mercury and Drop (`res/drawable-nodpi/theme_*.webp`, drawn by
`BundledArt.kt`, which decodes each once and keeps a small copy for the previews). Three are
drawn in code: Light on water (`caustics`), Zellige and City lights (`bokeh`). Theme IDs are
stable; a saved ID that no longer exists falls back to Chrome.

People can also use their own picture (`PhotoBackground.kt`, id `photo`). It is copied into
the app at screen size, the right way up, and the theme's colours are taken from it: its most
vivid colour, lightened, becomes the accent, and its average colour tints the glass. It is
dimmed by 30% and gets the usual darker middle, so text stays readable on any picture.

Glass looks real only where something behind its edges can bend: lines, light and colour. A
theme whose middle is plain black makes the glass read as a dark panel, however rich its edges.

## Rendering

There are two independent backdrop sources:

- Cards inside a page refract the cached wallpaper.
- The floating dock, + button and in-app download sheet refract a shared live recording of
  the page behind them, including scrolling content.

The page recording excludes the dock and sheet. Page descendants never receive their own
live source: sampling a layer while recording into that same layer would create a render loop.
Everything uses GPU display lists; there are no CPU screenshots or screen-capture permissions.
The live producer has its own display-list boundary, so repainting a floating control can reuse
the unchanged page instead of recording and invalidating it again.
Panel draws also observe their placement state: a list can move a cached child without drawing
it again, so querying coordinates only inside drawing leaves the old wallpaper sample attached
to the card. Each placement change invalidates that sample while the wallpaper stays fixed.

On Android 13 and later, `GlassRefraction.kt` uses an AGSL runtime shader. A rounded-rectangle
distance field bends the background near the bevel, slightly magnifies its centre, and adds
directional edge lighting and restrained colour dispersion. Frost precedes the lens; labels
and icons are drawn after it, so foreground text stays sharp. The sampling texture extends
beyond the visible panel to give the blur real neighbouring pixels.

Android 12 uses live background blur without the runtime lens. Older devices use translucent
tints and reflective edges. Effects-disabled mode uses opaque panels.

The cross-app share sheet uses a separate rounded, bottom-aligned native dialog. Android's
background blur follows that window's bounds rather than frosting the whole screen. Its native
backing replaces the in-app sheet's outer glass fill; a second frosted fill would obscure the
blur. The window handles system bars, keyboard resizing, outside taps and background dimming.
Galaxy phones switch Android's cross-window blur off (`adb shell wm disable-blur` reports
"Blur supported on device: false"), so `SamsungBlur.kt` then applies One UI's own blur
(`View.semSetBlurInfo`, found by reflection) to the same window. When neither blur is available,
or battery saver is on, its backing becomes opaque so sharp content from the other app cannot
show through the controls. Another app's pixels are not accessible to our
renderer, so the sheet carries a material of its own (`drawShareMaterial`: the theme's glass
tint, a faint copy of its artwork and accent glows). The sheet shows it through the AGSL lens,
bending it at the rim, and the cards and buttons inside refract it; the system blur shows
through all of it.

## Files

Paths are relative to `app/src/main/java/app/glint/`.

| File | Purpose |
| --- | --- |
| `ui/theme/Artworks.kt` | Drawn artwork, pictures, and density-safe recording helpers |
| `ui/theme/BundledArt.kt` | Decodes the pictures that ship with the app, full size and small |
| `ui/theme/Themes.kt` | Stable theme IDs, colours and translucent material fills |
| `ui/theme/Glass.kt` | Wallpaper/live sources, sampling, clipping and fallbacks |
| `ui/theme/GlassRefraction.kt` | Reusable GPU lens, geometric displacement and edge lighting |
| `ui/theme/ShareGlassDialog.kt` | Bounded native share-window blur and readable fallback |
| `ui/theme/SamsungBlur.kt` | One UI's window blur for Galaxy phones, by reflection |
| `ui/theme/GlassDialog.kt` | Dialog finish backed independently from the page window |
| `ui/ThemePicker.kt` | Artwork previews |

## Adding a theme

1. Either add a dark picture as `res/drawable-nodpi/theme_<id>.webp` (about 1000 × 1800, with
   its bright parts near the edges) and draw it with `drawBundled`, or draw an extension on
   `DrawScope` in `Artworks.kt`, using fractional positions for large shapes and `dp` for fine
   details. Keep the central content area readable.
2. Register an `AppTheme` in `Themes.kt` and add it to `AppThemes.all`. Keep its ID stable after
   release, choose a light accent with a dark `onPrimary`, and set a dark background/glass tint.
3. Add its name to `res/values/strings.xml`, and to the `values-*` translations you can write;
   the other languages show the English name until someone translates it.
4. Run unit tests, lint and the render tests before reviewing device screenshots.

Artwork should be deterministic and static. Use the `Seeded`, `at`, `drawStars` and
`cubicPoint` helpers for custom themes. Detail remains cached; do not add per-frame artwork
animation or capture a live page per card.

## Validation

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
# With an Android 13+ emulator already running:
./gradlew :app:connectedDebugAndroidTest -Pglint.abi=x86_64
```

The **Check liquid glass** pull-request workflow builds the app and runs Android tests on
Android 13 and Android 16 (API 33 and 36).
The tests require real Android hardware rendering. They check that AGSL compiles, an edge
samples a different stripe rather than merely recolouring it, and draw-only changes in the
source update pixels inside the glass without recomposing that glass. Another test verifies
that unrelated window redraws reuse the source and actual source changes refresh it.
A scrolling regression moves a tall cached card through a real lazy list and checks that fixed
viewport colours still match the wallpaper, including below the old sample's source boundary.
Native share-window tests cover bounded geometry, rounded backing, text input, capability and
effects changes, opaque fallback, source isolation, outside/Back dismissal, and the actual
download sheet's close action. Emulator checks cannot verify an OEM compositor's blur policy.
A gold-accented (Zellige) render harness uses shared UI controls for visual inspection. Test images are diagnostic renders,
not a complete application walkthrough.

The pinned Compose version needs an explicit independent density inside the live recording
block; otherwise `DrawScope.record` can refer its density back to itself. Keep that workaround
until the dependency includes AndroidX's September 2026 fix.
