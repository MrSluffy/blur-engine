# Views: `BlurView`

`BlurView` is a `FrameLayout`. It blurs what the window draws behind it, and its children draw on top, unblurred.

## Creating one

```kotlin
// Properties
val bar = BlurView(context).apply {
    blurRadius = 20 * density
    setTint(Color.WHITE, 0.15f)
}

// Builder DSL
val card = BlurView(context) {
    radius = 24 * density
    algorithm = BlurAlgorithm.AUTO
    overlay { color = Color.WHITE; alpha = 0.18f }
    shape { cornerRadius = 24 * density }
    performance { quality = BlurQuality.BALANCED; downsample = 4 }
}
```

## XML attributes

```xml
<dev.blurengine.mrsluffy.view.BlurView
    android:layout_width="match_parent"
    android:layout_height="96dp"
    app:blurRadius="24dp"
    app:blurTintColor="#FFFFFF"
    app:blurTintAlpha="0.2"
    app:blurCornerRadius="24dp" />
```

| Attribute | Format | Property |
| --- | --- | --- |
| `blurRadius` | dimension | `blurRadius` |
| `blurTintColor` | color | `tintColor` (set with `setTint`) |
| `blurTintAlpha` | float, 0..1 | `tintAlpha` (set with `setTint`) |
| `blurSaturation` | float | `saturation` |
| `blurBrightness` | float | `brightness` |
| `blurCornerRadius` | dimension | `cornerRadius` |
| `blurDownsample` | integer | `downsample` |
| `blurAlgorithm` | `auto`, `gaussian`, `dualKawase`, `box` | `algorithm` |
| `blurQuality` | `low`, `balanced`, `high`, `ultra` | `quality` |

Liquid glass and progressive blur have no XML attributes. Set `glass` and `gradient` in code.

## Properties

| Property | Type | Meaning |
| --- | --- | --- |
| `blurRadius` | `Float`, px | Blur strength; sigma = radius / 2. |
| `setTint(color, alpha)` | | Color drawn over the blur. `tintColor` and `tintAlpha` read it back. |
| `saturation` | `Float` | 1 = unchanged, 0 = grayscale, above 1 = more vivid. |
| `brightness` | `Float` | RGB multiplier, 1 = unchanged. |
| `cornerRadius` | `Float`, px | Clips the blur and the children to a rounded rectangle. |
| `downsample` | `Int` | Capture and blur at 1/downsample size. Default 4. |
| `algorithm` | `BlurAlgorithm` | `AUTO` (default), `GAUSSIAN`, `DUAL_KAWASE`, `BOX`. |
| `quality` | `BlurQuality` | `LOW`, `BALANCED` (default), `HIGH`, `ULTRA`. Only affects the GLES and software paths. |
| `glass` | `GlassStyle?` | [Liquid glass](effects.md#liquid-glass). |
| `gradient` | `BlurGradient?` | [Progressive blur](effects.md#progressive-blur). |
| `sourceView` | `View?` | What to blur. Null (default) means the whole window. |
| `config` | `BlurConfig` | All of the above as one immutable value. |
| `respectsAnimationScale` | `Boolean` | Follow the system animation scale. Default true. |

Setting any property applies immediately and stops running animations. To animate, see [Animation](animation.md).

### Diagnostics

| Member | Meaning |
| --- | --- |
| `activeRenderer` | The path used for the last frame: `"render-effect"`, `"gles"` or `"software"`. |
| `lastUpdateMillis` | Main-thread time of the last update. |
| `displayedConfig` | What is on screen now, including animation progress. |
| `invalidateBlur()` | Forces a new capture on the next frame. |

## `sourceView`

By default a `BlurView` captures the whole window behind it. Setting `sourceView` limits the capture to one view and its children:

```kotlin
blurView.sourceView = recyclerView
```

This is useful when:

- only one view is behind the blur, so capturing less is cheaper;
- the window contains content that can't be captured (see [Limitations](limitations.md)) and isn't behind the blur anyway.

The `BlurView` leaves itself out of the capture, so `sourceView` can also be a parent of it.

## Lifecycle

Resources are created when the view is attached to a window and released when it's detached. Nothing needs to be released by hand. Running animations jump to their end when the view is detached.

Off the RenderEffect path (API 29 and 30, or a non-`AUTO` algorithm), the blur backend is created on a background thread. For the first 100 to 400 ms after attaching, the view shows only its tint.
