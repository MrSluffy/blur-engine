# Compose: `Modifier.blurSurface`

`blur-compose` adds a modifier and a container. Both blur what the window draws behind the element; the element's own content draws on top.

```kotlin
Box(
    Modifier.blurSurface {
        radius = 25.dp
        tint(Color.White.copy(alpha = 0.15f))
        cornerRadius(24.dp)
    }
)

BlurContainer(blur = { radius = 32.dp; tint(Color.White.copy(alpha = 0.4f)) }) {
    Text("Frosted bar")   // BoxScope content, drawn on top
}
```

## Settings

| Setting | Type | Default | Meaning |
| --- | --- | --- | --- |
| `radius` | `Dp` | 20.dp | Blur strength; sigma = radius / 2. |
| `tint(color)` / `tint` | `Color` | transparent | Color over the blur; its alpha is the opacity. |
| `saturation` | `Float` | 1 | 1 = unchanged, 0 = grayscale. |
| `brightness` | `Float` | 1 | RGB multiplier. |
| `cornerRadius(dp)` | | none | Rounded clip. Also sets the shape liquid glass uses for its rim. |
| `shape` | `Shape?` | null | Any clip shape. Liquid glass needs `cornerRadius()` instead. |
| `glass(...)` | | off | [Liquid glass](effects.md#liquid-glass). |
| `gradient` | `BlurGradient?` | null | [Progressive blur](effects.md#progressive-blur). |
| `progress` | `Float` | 1 | 0..1 fraction of the radius and tint shown, for gestures. 0 skips the capture. |
| `algorithm` | `BlurAlgorithm` | `AUTO` | Leave on `AUTO`. |
| `quality` | `BlurQuality` | `BALANCED` | GLES and software paths only. |
| `downsample` | `Int` | 4 | Capture and blur at 1/downsample size. |
| `source` | `View?` | null | What to blur. Null means the whole window. |

## Animating

Use Compose's animation APIs, and read the animated value **inside** the block:

```kotlin
val radius by animateDpAsState(if (expanded) 48.dp else 12.dp, spring(), label = "blur")
Modifier.blurSurface { this.radius = radius }
```

The block runs during the frame, not during composition, and runs again when the state it reads changes. Animation frames therefore don't recompose. A device test checks this: an animation caused one recomposition (for the target change) and none for its frames.

!!! tip "Read state inside the block"
    `val r = anim.value` outside the block and `radius = r` inside it still works, but recomposes on every frame. Pass the `State` and read `.value` inside instead.

Gesture-driven blur works the same way with `progress`:

```kotlin
// sheetFraction: State<Float>, 0 = closed, 1 = open
Modifier.blurSurface { radius = 32.dp; progress = sheetFraction.value }
```

## Name shadowing

A local variable with the same name as a setting (for example `val radius = ...`) shadows that setting inside the block, because Kotlin gives locals precedence over receiver members. Write `this.radius = radius`, or rename the local.

## Shared engine

`blurSurface` uses the same renderer as `BlurView`. It has the same render paths, capture, warm-up and [limitations](limitations.md).
