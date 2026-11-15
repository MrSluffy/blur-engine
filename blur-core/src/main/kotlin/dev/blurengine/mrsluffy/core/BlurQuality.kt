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

package dev.blurengine.mrsluffy.core

/**
 * Quality presets. Each preset tunes algorithm-specific knobs, not the downsample factor
 * (that is set explicitly in [BlurConfig.downsample]).
 *
 * @property kawaseMaxIterations upper bound on Dual Kawase down/up levels.
 * @property boxPasses number of successive box passes (3 approximates a Gaussian).
 * @property gaussianSigmaSpan kernel half-width expressed in standard deviations.
 */
public enum class BlurQuality(
    public val kawaseMaxIterations: Int,
    public val boxPasses: Int,
    public val gaussianSigmaSpan: Float,
) {
    LOW(kawaseMaxIterations = 3, boxPasses = 1, gaussianSigmaSpan = 2f),
    BALANCED(kawaseMaxIterations = 4, boxPasses = 2, gaussianSigmaSpan = 2.5f),
    HIGH(kawaseMaxIterations = 5, boxPasses = 3, gaussianSigmaSpan = 3f),
    ULTRA(kawaseMaxIterations = 6, boxPasses = 4, gaussianSigmaSpan = 3.5f),
}
