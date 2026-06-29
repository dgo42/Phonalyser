/*
 * Phonalyser — precision audio measurement workbench.
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.edgo.audio.measure.gui.scope.gl;

import org.edgo.audio.measure.gui.common.NvgMeasurementPainter;
import org.edgo.audio.measure.preferences.Preferences;
import org.lwjgl.nanovg.NVGColor;
import org.lwjgl.nanovg.NVGPaint;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;

import static org.lwjgl.nanovg.NanoVG.*;

/**
 * GPU display persistence ("digital phosphor") for the oscilloscope, shared by the
 * Windows / Linux ({@link SwtGlCanvasSurface}, NanoVGGL2) and macOS
 * ({@link SwtGlChildSurface}, NanoVGGL3) surfaces.  It owns the off-screen state; the
 * surface owns the GL context and presents the result.
 *
 * <p>The waveform ({@link GlScopeRenderer.Phase#TRACE}) is routed through two
 * off-screen RGBA16F framebuffers instead of straight to the screen:
 * <ul>
 *   <li>the <b>scratch</b> buffer holds just the current frame's trace.  It carries a
 *       depth/stencil attachment because NanoVG is created with {@code NVG_STENCIL_STROKES}
 *       and the anti-aliased stroke path needs a stencil buffer to suppress self-overlap;</li>
 *   <li>the <b>phosphor</b> buffer is the decayed accumulation.  On each genuinely new
 *       captured frame it is multiplied down by {@code exp(-dt/tau)} (frame-rate-independent
 *       decay) and the scratch trace is composited in; a held frame leaves it; a UI gesture
 *       ({@link Kind#RESET}) resets it; an expose ({@link Kind#COMPOSITE}) just re-composites
 *       it.  An empty buffer (first frame / just resized) is seeded from the current trace so
 *       the persisted layer is never blank.</li>
 * </ul>
 * The buffers use raw {@link GL30} framebuffer objects — LWJGL 3.3.3 ships no nvglu
 * framebuffer helper — sized in physical pixels ({@link GlFrameSize#pixelW}); the colour
 * textures are wrapped as NanoVG images via the injected {@link NvgImageFactory} (the only
 * GL2-vs-GL3 difference) so they can be composited with {@code nvgImagePattern}.
 */
final class ScopePhosphor {

    /** Upper bound on a single decay step's {@code dt} so resuming after a long pause
     *  (the realtime loop was idle, so the decay clock didn't advance) fades the
     *  afterglow gently over a few frames instead of wiping it to black in one. */
    private static final double MAX_DECAY_SECONDS = 0.5;

    /** Wraps an existing GL colour texture as a NanoVG image.  The
     *  {@code nvglCreateImageFromHandle} call (and its image flags) differ between the GL2
     *  and GL3 backends, so the surface injects the right one. */
    @FunctionalInterface
    interface NvgImageFactory {
        int create(long vg, int textureId, int w, int h);
    }

    /** Which kind of frame is being rendered — selects the persistence behaviour. */
    enum Kind {
        /** Realtime loop: decay + accumulate the trace, but only if it's genuinely new. */
        REALTIME,
        /** UI gesture / settings change: re-render the trace and reset the afterglow. */
        RESET,
        /** Expose / resize: re-composite the frozen phosphor (no decay, no accumulate). */
        COMPOSITE;

        private Kind() {}
    }

    private final long vg;
    private final NvgMeasurementPainter painter;
    private final NvgImageFactory imageFactory;

    private int  scratchFbo;
    private int  scratchTex;
    private int  scratchRbo;        // packed depth-stencil — NanoVG stencil-stroke path
    private int  scratchImg;        // scratchTex wrapped as a NanoVG image
    private int  phosphorFbo;
    private int  phosphorTex;
    private int  phosphorImg;       // phosphorTex wrapped as a NanoVG image
    private int  fboW;
    private int  fboH;
    private long lastAccumNanos;
    private boolean haveLastAccum;  // false until the first accumulate / after a reset

    // This frame's dimensions — set at the top of render(), read by the helpers.
    private int   logicalW;
    private int   logicalH;
    private int   pixelW;
    private int   pixelH;
    private float pixelRatio;

    ScopePhosphor(long vg, NvgMeasurementPainter painter, NvgImageFactory imageFactory) {
        this.vg           = vg;
        this.painter      = painter;
        this.imageFactory = imageFactory;
    }

    /**
     * Renders one persisted frame and composites it to the current (default) framebuffer.
     * Does <b>not</b> swap buffers — the surface presents.  Returns {@code false} (having
     * released its buffers) when persistence is off or the framebuffers can't be created,
     * so the surface falls back to a direct full render.
     */
    boolean render(GlScopeRenderer renderer, Kind kind, GlFrameSize size) {
        double persistSeconds = persistenceSeconds();   // 0 = off, < 0 = infinite, > 0 = decay time
        if (persistSeconds == 0.0) {
            release();
            return false;
        }
        logicalW   = size.logicalW();
        logicalH   = size.logicalH();
        pixelW     = size.pixelW();
        pixelH     = size.pixelH();
        pixelRatio = size.pixelRatio();
        if (!ensureBuffers()) {
            release();
            return false;
        }

        boolean traced = false;
        switch (kind) {
            case REALTIME -> {
                renderTraceToScratch(renderer);             // also sets renderer.isLastFrameNew()
                traced = true;
                if (renderer.isLastFrameNew()) accumulate(persistSeconds);
            }
            case RESET -> {
                renderTraceToScratch(renderer);
                traced = true;
                resetPhosphor();
            }
            case COMPOSITE -> { /* phosphor frozen — just re-composite below */ }
        }
        // Seed an empty phosphor (first frame, or a resize just reallocated + cleared it) from
        // the current trace, so the persisted layer is never blank — vital for a STOPPED scope,
        // whose only repaint is a COMPOSITE expose / resize and which the realtime loop won't refill.
        if (!haveLastAccum) {
            if (!traced) renderTraceToScratch(renderer);
            resetPhosphor();
        }
        compositeToScreen(renderer);
        return true;
    }

    /** Frees the framebuffers / textures / images.  Call with the GL context current. */
    void release() {
        if (scratchImg  != 0) { nvgDeleteImage(vg, scratchImg);  scratchImg  = 0; }
        if (phosphorImg != 0) { nvgDeleteImage(vg, phosphorImg); phosphorImg = 0; }
        if (scratchFbo  != 0) { GL30.glDeleteFramebuffers(scratchFbo);   scratchFbo  = 0; }
        if (phosphorFbo != 0) { GL30.glDeleteFramebuffers(phosphorFbo);  phosphorFbo = 0; }
        if (scratchRbo  != 0) { GL30.glDeleteRenderbuffers(scratchRbo);  scratchRbo  = 0; }
        if (scratchTex  != 0) { GL11.glDeleteTextures(scratchTex);   scratchTex  = 0; }
        if (phosphorTex != 0) { GL11.glDeleteTextures(phosphorTex);  phosphorTex = 0; }
        fboW = 0;
        fboH = 0;
        haveLastAccum = false;
    }

    /** Renders just the current trace into the (cleared) scratch buffer.  Runs
     *  {@code drawWaveforms}, so afterwards {@link GlScopeRenderer#isLastFrameNew()} is current. */
    private void renderTraceToScratch(GlScopeRenderer renderer) {
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, scratchFbo);
        GL11.glViewport(0, 0, pixelW, pixelH);
        GL11.glClearColor(0f, 0f, 0f, 0f);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_STENCIL_BUFFER_BIT);
        nvgBeginFrame(vg, logicalW, logicalH, pixelRatio);
        painter.reset(logicalW, logicalH, pixelRatio);
        renderer.renderGl(painter, logicalW, logicalH, GlScopeRenderer.Phase.TRACE);
        nvgEndFrame(vg);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
    }

    /** New captured frame: decay the phosphor by {@code exp(-dt/tau)} (skipped for the
     *  first frame and for infinite persistence) then composite the scratch trace into it. */
    private void accumulate(double persistSeconds) {
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, phosphorFbo);
        GL11.glViewport(0, 0, pixelW, pixelH);
        nvgBeginFrame(vg, logicalW, logicalH, pixelRatio);
        painter.reset(logicalW, logicalH, pixelRatio);
        long now = System.nanoTime();
        if (persistSeconds > 0.0 && haveLastAccum) {
            double dt   = Math.min((now - lastAccumNanos) * 1e-9, MAX_DECAY_SECONDS);
            float  fade = (float) (1.0 - Math.exp(-dt / persistSeconds));
            decay(fade);
        }
        // (Infinite persistence, persistSeconds < 0: never decay — just keep accumulating.)
        blitImage(scratchImg);
        nvgEndFrame(vg);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        lastAccumNanos = now;
        haveLastAccum  = true;
    }

    /** UI gesture / seed: wipe the (possibly stale-coordinate) afterglow and stamp the
     *  freshly re-rendered trace, so the trace tracks the gesture instead of smearing. */
    private void resetPhosphor() {
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, phosphorFbo);
        GL11.glViewport(0, 0, pixelW, pixelH);
        GL11.glClearColor(0f, 0f, 0f, 0f);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
        nvgBeginFrame(vg, logicalW, logicalH, pixelRatio);
        painter.reset(logicalW, logicalH, pixelRatio);
        blitImage(scratchImg);
        nvgEndFrame(vg);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        // The reset is itself a phosphor update: the buffer now holds valid content and the
        // decay clock starts here, so the next new frame decays gently against this instant.
        lastAccumNanos = System.nanoTime();
        haveLastAccum  = true;
    }

    /** Composites the frame to the screen: fresh graticule, the persisted trace, fresh overlay. */
    private void compositeToScreen(GlScopeRenderer renderer) {
        GL11.glViewport(0, 0, pixelW, pixelH);
        GL11.glClearColor(0f, 0f, 0f, 1f);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT | GL11.GL_STENCIL_BUFFER_BIT);
        nvgBeginFrame(vg, logicalW, logicalH, pixelRatio);
        painter.reset(logicalW, logicalH, pixelRatio);
        renderer.renderGl(painter, logicalW, logicalH, GlScopeRenderer.Phase.BACKDROP);
        blitImage(phosphorImg);
        renderer.renderGl(painter, logicalW, logicalH, GlScopeRenderer.Phase.OVERLAY);
        nvgEndFrame(vg);
    }

    /** Multiplies the bound buffer by {@code 1 - fade} (= {@code exp(-dt/tau)}) on every
     *  channel — a black quad with blend factors {@code (ZERO, ONE_MINUS_SRC_ALPHA)}:
     *  {@code dst = 0 + dst*(1 - fade)}.  Restores premultiplied source-over afterwards. */
    private void decay(float fade) {
        nvgGlobalCompositeBlendFunc(vg, NVG_ZERO, NVG_ONE_MINUS_SRC_ALPHA);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            NVGColor col = NVGColor.malloc(stack);
            nvgRGBAf(0f, 0f, 0f, fade, col);
            nvgBeginPath(vg);
            nvgRect(vg, 0, 0, logicalW, logicalH);
            nvgFillColor(vg, col);
            nvgFill(vg);
        }
        nvgGlobalCompositeOperation(vg, NVG_SOURCE_OVER);
    }

    /** Draws an FBO colour texture over the current frame (premultiplied source-over). */
    private void blitImage(int image) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            NVGPaint paint = NVGPaint.malloc(stack);
            nvgImagePattern(vg, 0, 0, logicalW, logicalH, 0f, image, 1f, paint);
            nvgBeginPath(vg);
            nvgRect(vg, 0, 0, logicalW, logicalH);
            nvgFillPaint(vg, paint);
            nvgFill(vg);
        }
    }

    /** Ensures the scratch + phosphor framebuffers exist at the physical frame size,
     *  (re)allocating on resize.  Returns {@code false} (and releases) if either is incomplete. */
    private boolean ensureBuffers() {
        if (scratchFbo != 0 && fboW == pixelW && fboH == pixelH) return true;
        release();

        // Scratch: float colour + a packed depth-stencil renderbuffer for stencil strokes.
        scratchTex = newFloatTexture();
        scratchFbo = GL30.glGenFramebuffers();
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, scratchFbo);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                                    GL11.GL_TEXTURE_2D, scratchTex, 0);
        scratchRbo = GL30.glGenRenderbuffers();
        GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, scratchRbo);
        GL30.glRenderbufferStorage(GL30.GL_RENDERBUFFER, GL30.GL_DEPTH24_STENCIL8, pixelW, pixelH);
        GL30.glFramebufferRenderbuffer(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_STENCIL_ATTACHMENT,
                                       GL30.GL_RENDERBUFFER, scratchRbo);
        boolean scratchOk = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER) == GL30.GL_FRAMEBUFFER_COMPLETE;

        // Phosphor: float colour only (it only ever takes convex fills + blits — no stencil).
        phosphorTex = newFloatTexture();
        phosphorFbo = GL30.glGenFramebuffers();
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, phosphorFbo);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                                    GL11.GL_TEXTURE_2D, phosphorTex, 0);
        boolean phosphorOk = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER) == GL30.GL_FRAMEBUFFER_COMPLETE;
        GL11.glClearColor(0f, 0f, 0f, 0f);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);   // start the accumulation transparent
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);

        if (!scratchOk || !phosphorOk) {
            release();
            return false;
        }

        scratchImg  = imageFactory.create(vg, scratchTex,  pixelW, pixelH);
        phosphorImg = imageFactory.create(vg, phosphorTex, pixelW, pixelH);
        fboW = pixelW;
        fboH = pixelH;
        haveLastAccum = false;
        return true;
    }

    /** Allocates a physical-size RGBA16F texture (float colour — an 8-bit buffer has an integer
     *  decay floor that leaves a faint permanent ghost; RGBA16F decays cleanly to zero). */
    private int newFloatTexture() {
        int tex = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RGBA16F, pixelW, pixelH, 0,
                          GL11.GL_RGBA, GL11.GL_FLOAT, (ByteBuffer) null);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        return tex;
    }

    /** Resolved persistence time from preferences: 0 = off, &lt; 0 = infinite, &gt; 0 = decay seconds. */
    private double persistenceSeconds() {
        Preferences prefs = Preferences.instance();
        return prefs.getOscPersistenceMode().effectiveSeconds(prefs.getOscPersistenceManualSeconds());
    }
}
