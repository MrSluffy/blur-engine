# Getting started

## Requirements

- `minSdk` 29 (Android 10) or higher.
- Hardware acceleration, which is on by default.
- For Compose: the library is built with Compose BOM 2024.09.00 and Kotlin 2.0.20. Newer versions work.

## Install

The artifacts are on Maven Central:

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}
```

```kotlin
// app/build.gradle.kts
dependencies {
    implementation("io.github.mrsluffy:blur-view:1.0.0")     // Android Views
    implementation("io.github.mrsluffy:blur-compose:1.0.0")  // Jetpack Compose (includes blur-view)
}
```

Add only `blur-view` for a View-only app; it doesn't pull in Compose. All packages are under `dev.blurengine.mrsluffy`.

## Your first blur

A blur surface blurs whatever is drawn **behind** it in the window. Place it on top of the content you want blurred, for example as the last child of a `FrameLayout` or `Box`.

=== "Views"

    ```kotlin
    import dev.blurengine.mrsluffy.view.BlurView

    val density = resources.displayMetrics.density
    val bar = BlurView(context).apply {
        blurRadius = 24 * density          // pixels
        setTint(Color.WHITE, 0.2f)
        cornerRadius = 20 * density
    }
    root.addView(bar, FrameLayout.LayoutParams(MATCH_PARENT, (96 * density).toInt(), Gravity.TOP))
    ```

    Or in XML:

    ```xml
    <FrameLayout ...>
        <androidx.recyclerview.widget.RecyclerView ... />

        <dev.blurengine.mrsluffy.view.BlurView
            android:layout_width="match_parent"
            android:layout_height="96dp"
            app:blurRadius="24dp"
            app:blurTintColor="#FFFFFF"
            app:blurTintAlpha="0.2"
            app:blurCornerRadius="20dp">
            <!-- children draw on top, sharp -->
        </dev.blurengine.mrsluffy.view.BlurView>
    </FrameLayout>
    ```

=== "Compose"

    ```kotlin
    import dev.blurengine.mrsluffy.compose.blurSurface

    Box(Modifier.fillMaxSize()) {
        LazyColumn { /* content */ }

        Box(
            Modifier.fillMaxWidth().height(96.dp).blurSurface {
                radius = 24.dp
                tint(Color.White.copy(alpha = 0.2f))
                cornerRadius(20.dp)
            }
        ) {
            Text("Title")   // drawn on top, sharp
        }
    }
    ```

## Choosing settings

| Setting | Default | Notes |
| --- | --- | --- |
| Radius | 20 px (View), 20 dp (Compose) | Blur strength; sigma = radius / 2. 16 to 40 dp suits most UI. |
| Tint | none | A translucent color over the blur. Light themes: white at 0.1 to 0.3. Dark themes: a dark color at 0.3 to 0.5. |
| Saturation | 1 | Above 1 makes the blur more vivid, a common glass look (1.2 to 1.4). |
| Downsample | 4 | The capture is blurred at 1/4 size. Higher is faster and softer. Keep the default unless the radius is very small. |
| Algorithm | `AUTO` | Leave it on `AUTO`. See [How it works](how-it-works.md) for the others. |

## Next steps

- [Views](views.md) and [Compose](compose.md) for the full API.
- [Effects](effects.md) for liquid glass, progressive blur and stacking.
- [Animation](animation.md) for show / hide, springs and gestures.
- [Recipes](recipes.md) for common screens.
