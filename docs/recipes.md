# Recipes

## Frosted toolbar over a list (Views)

Let the list scroll under the toolbar by padding it and turning off `clipToPadding`:

```xml
<FrameLayout
    android:layout_width="match_parent"
    android:layout_height="match_parent">

    <androidx.recyclerview.widget.RecyclerView
        android:id="@+id/list"
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        android:clipToPadding="false"
        android:paddingTop="88dp" />

    <dev.blurengine.mrsluffy.view.BlurView
        android:id="@+id/toolbar"
        android:layout_width="match_parent"
        android:layout_height="88dp"
        app:blurRadius="32dp"
        app:blurTintColor="#FFFFFF"
        app:blurTintAlpha="0.4">

        <TextView
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_gravity="bottom|start"
            android:padding="20dp"
            android:text="Inbox"
            android:textSize="20sp" />
    </dev.blurengine.mrsluffy.view.BlurView>
</FrameLayout>
```

```kotlin
findViewById<BlurView>(R.id.toolbar).sourceView = findViewById(R.id.list)   // optional, captures less
```

## Floating glass navigation bar (Compose)

```kotlin
Box(Modifier.fillMaxSize()) {
    LazyColumn(contentPadding = PaddingValues(bottom = 120.dp)) { /* items */ }

    Row(
        Modifier.align(Alignment.BottomCenter)
            .navigationBarsPadding()
            .padding(horizontal = 28.dp, vertical = 16.dp)
            .fillMaxWidth()
            .height(64.dp)
            .blurSurface {
                radius = 24.dp
                tint(Color.White.copy(alpha = 0.10f))
                saturation = 1.4f
                cornerRadius(32.dp)
                glass(refraction = 8.dp, edgeWidth = 14.dp, highlight = 0.75f)
            },
    ) { /* tabs */ }
}
```

## Progressive blur under the status bar (Compose)

Draw edge to edge, then put a gradient blur over the top inset so content fades out of focus as it scrolls under the status bar:

```kotlin
Box(Modifier.fillMaxSize()) {
    LazyColumn { /* items */ }

    Box(
        Modifier.fillMaxWidth()
            .windowInsetsTopHeight(WindowInsets.statusBars.add(WindowInsets(top = 48.dp)))
            .blurSurface {
                radius = 32.dp
                gradient = BlurGradient.BottomToTop
                tint(Color.Black.copy(alpha = 0.25f))
            },
    )
}
```

## Blur that follows a bottom sheet (Views)

`setBlurProgress` maps 0..1 to no blur..full blur without starting an animation, so it can follow a drag frame by frame:

```kotlin
val scrim = BlurView(context).apply {
    blurRadius = 24 * density
    setTint(Color.BLACK, 0.3f)
    setBlurProgress(0f)
}

BottomSheetBehavior.from(sheet).addBottomSheetCallback(object : BottomSheetBehavior.BottomSheetCallback() {
    override fun onSlide(bottomSheet: View, slideOffset: Float) = scrim.setBlurProgress(slideOffset.coerceIn(0f, 1f))
    override fun onStateChanged(bottomSheet: View, newState: Int) = Unit
})
```

At progress 0 the capture is skipped, so a closed sheet costs nothing.

## Show a blurred overlay (Views)

```kotlin
overlay.show(duration = 300L, radius = 28 * density)   // also makes the view VISIBLE

// later
overlay.hide(duration = 250L) { finished -> if (finished) parent.removeView(overlay) }
```

`hide()` leaves the view `INVISIBLE` when it finishes. If `show()` interrupts it, the view stays visible and `finished` is false.

## Blur a bitmap off the main thread

The backends also work bitmap to bitmap, without a view:

```kotlin
val config = BlurConfig(radius = 24f)
val backend = BlurBackendSelector().selectAndInitialize(config, DeviceCapabilitiesDetector.detect(context))
try {
    backend.render(BitmapImage(source), BitmapImage(output))   // background thread
} finally {
    backend.release()
}
```

Both bitmaps must be `ARGB_8888`, and `output` must be mutable. The backend keeps no reference after `render` returns. See [How it works](how-it-works.md#bitmap-backends).
