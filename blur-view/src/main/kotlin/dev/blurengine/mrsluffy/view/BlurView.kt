/*
 * Copyright 2026 MrSluffy
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.blurengine.mrsluffy.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Outline
import android.provider.Settings
import android.util.AttributeSet
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import dev.blurengine.mrsluffy.animation.BlurAnimationBuilder
import dev.blurengine.mrsluffy.animation.BlurAnimationListener
import dev.blurengine.mrsluffy.animation.BlurAnimator
import dev.blurengine.mrsluffy.animation.BlurProperty
import dev.blurengine.mrsluffy.animation.BlurValues
import dev.blurengine.mrsluffy.animation.blurAnimation
import dev.blurengine.mrsluffy.capture.CaptureGuard
import dev.blurengine.mrsluffy.render.ChoreographerFrameClock
import dev.blurengine.mrsluffy.core.BlurAlgorithm
import dev.blurengine.mrsluffy.core.BlurConfig
import dev.blurengine.mrsluffy.core.BlurConfigBuilder
import dev.blurengine.mrsluffy.core.BlurGradient
import dev.blurengine.mrsluffy.core.BlurQuality
import dev.blurengine.mrsluffy.core.GlassStyle
import dev.blurengine.mrsluffy.core.blurConfig

/**
 * A container that shows a blurred copy of what is behind it, with optional tint, color
 * adjustment and rounded corners. Children are drawn on top, unblurred.
 *
 * ```
 * BlurView(context).apply {
 *     blurRadius = 20f
 *     setTint(Color.WHITE, 0.15f)
 * }
 * ```
 *
 * By default the whole window ([View.getRootView]) is captured. Set [sourceView] to capture a
 * smaller subtree, which is cheaper. The blur updates on every frame the window draws, so scrolling
 * and animations behind the view are followed automatically.
 *
 * Stacking: a blur view's blur includes the blur views drawn below it, so glass panels can be
 * layered. Views drawn above it are left out, which also prevents feedback between views.
 *
 * Limits: content that software drawing cannot render is missing from the blur. That covers
 * SurfaceView and TextureView (video, camera, maps), and HARDWARE bitmaps used in a shader.
 */
public open class BlurView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    /** This view as the renderer sees it; also its identity for [CaptureGuard] during captures. */
    private val blurTarget = object : BlurTarget {
        override val targetWidth: Int get() = width
        override val targetHeight: Int get() = height
        override fun getTargetLocationInWindow(out: IntArray) = getLocationInWindow(out)
        override val isTargetShown: Boolean get() = isShown
        override fun invalidateTarget() = invalidate()
    }

    private val controller = BlurSurfaceRenderer(this, blurTarget)

    /** The configured ("shown") state. Animations and progress move the displayed state relative to it. */
    private var resting = BlurConfig()
    private var animator: BlurAnimator? = null

    /** Builds the view and configures it with the [blurConfig] DSL. */
    public constructor(context: Context, configure: BlurConfigBuilder.() -> Unit) : this(context) {
        config = blurConfig(config, configure)
    }

    /**
     * The configured blur: what [show] returns to and what [animateBlur] targets update. Setting
     * it applies immediately and stops running animations. See [displayedConfig] for what is on
     * screen mid-animation. The property setters below each replace one field of it.
     */
    public var config: BlurConfig
        get() = resting
        set(value) {
            resting = value
            animator?.snapTo(BlurValues.from(value))
            display(value)
        }

    /** What is drawn right now, including animation and [setBlurProgress]. */
    public val displayedConfig: BlurConfig get() = controller.config

    /**
     * When true (default), animations follow the system animation scale: scaled durations, and
     * jumping straight to the end when animations are turned off (reduced motion).
     */
    public var respectsAnimationScale: Boolean = true

    private fun display(value: BlurConfig) {
        val cornerChanged = value.cornerRadius != controller.config.cornerRadius
        controller.config = value
        if (cornerChanged) updateOutline()
        invalidate()
    }

    /** What to blur. Null (the default) means the whole window. Must be in the same window. */
    public var sourceView: View?
        get() = controller.source
        set(value) {
            controller.source = value
            invalidate()
        }

    /** Blur radius in pixels; sigma = radius / 2. */
    public var blurRadius: Float
        get() = config.radius
        set(value) { config = config.copy(radius = value) }

    public var tintColor: Int
        get() = config.tintColor
        set(value) { config = config.copy(tintColor = value) }

    /** Tint opacity, 0..1. */
    public var tintAlpha: Float
        get() = config.tintAlpha
        set(value) { config = config.copy(tintAlpha = value) }

    /** 1 = unchanged, 0 = grayscale. */
    public var saturation: Float
        get() = config.saturation
        set(value) { config = config.copy(saturation = value) }

    /** RGB multiplier, 1 = unchanged. */
    public var brightness: Float
        get() = config.brightness
        set(value) { config = config.copy(brightness = value) }

    /** Corner radius in pixels. Children are clipped too. */
    public var cornerRadius: Float
        get() = config.cornerRadius
        set(value) { config = config.copy(cornerRadius = value) }

    /** Capture and blur at 1/downsample resolution. Higher is faster and softer. */
    public var downsample: Int
        get() = config.downsample
        set(value) { config = config.copy(downsample = value) }

    public var algorithm: BlurAlgorithm
        get() = config.algorithm
        set(value) { config = config.copy(algorithm = value) }

    public var quality: BlurQuality
        get() = config.quality
        set(value) { config = config.copy(quality = value) }

    /**
     * Progressive blur, e.g. `BlurGradient.BottomToTop` for a status-bar scrim that fades out
     * downward. Null (the default) blurs uniformly.
     */
    public var gradient: BlurGradient?
        get() = config.gradient
        set(value) { config = config.copy(gradient = value) }

    /**
     * Liquid glass: rim highlight, gloss and edge shading on every API level, plus refraction and
     * dispersion on API 33+. The rim follows [cornerRadius]. Null (the default) is plain frosted blur.
     */
    public var glass: GlassStyle?
        get() = config.glass
        set(value) { config = config.copy(glass = value) }

    /** Diagnostic: which renderer drew the last frame ("render-effect", "gles", "software"), or null. */
    public val activeRenderer: String? get() = controller.activePath

    /** Diagnostic: main-thread milliseconds spent on the last capture and blur. */
    public val lastUpdateMillis: Float get() = controller.lastUpdateMillis

    /** Diagnostic for tests: the last update skipped capture because nothing needed blurring. */
    internal val skippedCapture: Boolean get() = controller.skippedCapture

    /** Diagnostic for tests: the render path for the current config needs no more warm-up. */
    internal val isRendererReady: Boolean get() = controller.isReady

    /** Diagnostic for tests: the last frame shows the current config with its final render path. */
    internal val isSettled: Boolean get() = controller.isSettled

    /** Diagnostic for tests: the last frame applied glass refraction (API 33+). */
    internal val isRefracting: Boolean get() = controller.refractionActive

    public fun setTint(color: Int, alpha: Float) {
        config = config.copy(tintColor = color, tintAlpha = alpha)
    }

    /** Forces a capture now, for example after content behind changed without redrawing the window. */
    public fun invalidateBlur() {
        controller.update()
        invalidate()
    }

    // ---- Animation ----

    /**
     * Animates to new values and makes them the configured state ([config]).
     *
     * ```
     * blurView.animateBlur { radius = 30f; duration = 300L }
     * blurView.animateBlur { radius = 30f; spring { dampingRatio = 0.75f; stiffness = 350f } }
     * blurView.animateBlur { radius(0f, 30f); tintAlpha(0f, 0.2f); spring() }
     * ```
     *
     * Interruptible: starts from what is on screen, and springs keep their velocity. Properties
     * not mentioned keep animating if they already were. Must be called on the main thread.
     */
    public fun animateBlur(block: BlurAnimationBuilder.() -> Unit) {
        val animation = blurAnimation(block)
        val target = BlurValues.from(resting)
        for (p in BlurProperty.values()) if (animation.animates(p)) target[p] = animation.target(p)
        resting = target.toConfig(resting)
        animator().start(animation)
    }

    /**
     * Makes the view visible and animates blur and tint in from nothing to the configured values.
     * [radius] also becomes the configured radius. Children appear immediately; they are not faded.
     */
    @JvmOverloads
    public fun show(duration: Long = 300L, radius: Float = resting.radius, onEnd: BlurAnimationListener? = null) {
        require(radius >= 0f) { "radius must be >= 0" }
        resting = resting.copy(radius = radius)
        val a = animator()
        if (visibility != VISIBLE) {
            a.snap(BlurProperty.RADIUS, 0f)
            a.snap(BlurProperty.TINT_ALPHA, 0f)
            visibility = VISIBLE
        }
        val targetRadius = resting.radius
        val targetAlpha = resting.tintAlpha
        a.start(blurAnimation {
            this.radius = targetRadius
            tintAlpha = targetAlpha
            this.duration = duration
            this.onEnd = onEnd
        })
    }

    /**
     * Animates blur and tint out to nothing, then makes the view [INVISIBLE]. If another animation
     * interrupts it, the view stays visible and [onEnd] gets finished = false.
     */
    @JvmOverloads
    public fun hide(duration: Long = 250L, onEnd: BlurAnimationListener? = null) {
        animator().start(blurAnimation {
            radius = 0f
            tintAlpha = 0f
            this.duration = duration
            this.onEnd = BlurAnimationListener { finished ->
                if (finished) visibility = INVISIBLE
                onEnd?.onEnd(finished)
            }
        })
    }

    /**
     * Sets blur and tint to a fraction of the configured values, for gesture-driven effects such
     * as a bottom sheet that blurs the background as it expands. 0 = no blur (and no capture
     * cost), 1 = [config]. Stops running animations.
     */
    public fun setBlurProgress(progress: Float) {
        val p = progress.coerceIn(0f, 1f)
        val a = animator()
        a.cancel()
        a.snap(BlurProperty.RADIUS, resting.radius * p)
        a.snap(BlurProperty.TINT_ALPHA, resting.tintAlpha * p)
        if (p > 0f && visibility != VISIBLE) visibility = VISIBLE
        display(a.values.toConfig(resting))
    }

    /** True while an animation started by [animateBlur], [show] or [hide] is running. */
    public val isAnimating: Boolean get() = animator?.isRunning == true

    /** Stops animations where they are. */
    public fun cancelBlurAnimation() {
        animator?.cancel()
    }

    private fun animator(): BlurAnimator = animator ?: BlurAnimator(
        clock = ChoreographerFrameClock(),
        initial = BlurValues.from(controller.config),
        durationScale = ::animationScale,
        onUpdate = { values -> display(values.toConfig(resting)) },
    ).also { animator = it }

    private fun animationScale(): Float =
        if (!respectsAnimationScale) 1f
        else Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)

    init {
        setWillNotDraw(false)
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, config.cornerRadius)
            }
        }
        if (attrs != null) readAttributes(context, attrs, defStyleAttr)
        updateOutline()
    }

    private fun readAttributes(context: Context, attrs: AttributeSet, defStyleAttr: Int) {
        val a = context.obtainStyledAttributes(attrs, R.styleable.BlurView, defStyleAttr, 0)
        try {
            val base = BlurConfig()
            resting = base.copy(
                radius = a.getDimension(R.styleable.BlurView_blurRadius, base.radius),
                tintColor = a.getColor(R.styleable.BlurView_blurTintColor, base.tintColor),
                tintAlpha = a.getFloat(R.styleable.BlurView_blurTintAlpha, base.tintAlpha),
                saturation = a.getFloat(R.styleable.BlurView_blurSaturation, base.saturation),
                brightness = a.getFloat(R.styleable.BlurView_blurBrightness, base.brightness),
                cornerRadius = a.getDimension(R.styleable.BlurView_blurCornerRadius, base.cornerRadius),
                downsample = a.getInt(R.styleable.BlurView_blurDownsample, base.downsample),
                algorithm = BlurAlgorithm.values()[a.getInt(R.styleable.BlurView_blurAlgorithm, base.algorithm.ordinal)],
                quality = BlurQuality.values()[a.getInt(R.styleable.BlurView_blurQuality, base.quality.ordinal)],
            )
            controller.config = resting
        } finally {
            a.recycle()
        }
    }

    private fun updateOutline() {
        clipToOutline = config.cornerRadius > 0f
        invalidateOutline()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        controller.attach()
    }

    override fun onDetachedFromWindow() {
        // Finish animations rather than leave them half-way; a running hide() still ends hidden.
        animator?.end()
        controller.detach()
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        invalidateOutline()
    }

    override fun draw(canvas: Canvas) {
        // Leave this view (and its children) out of any capture, including its own.
        // Blur views below the capturing one still draw, so stacked glass shows through.
        if (CaptureGuard.shouldSkip(canvas, blurTarget)) return
        super.draw(canvas)
    }

    override fun onDraw(canvas: Canvas) {
        controller.draw(canvas)
        super.onDraw(canvas)
    }
}
