/*
 * Phonalyser web — precision audio measurement workbench (browser port).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 */

// Faithful port of org.edgo.audio.measure.gui.scope.gl.ScopePhosphor
// (GPU display persistence / "digital phosphor" for the oscilloscope) and the
// per-frame kind-decision block of its render() (Java :151-187).
//
// The Java surface is GPU-only: it routes the trace through two off-screen
// RGBA16F framebuffers, decays the accumulation by exp(-dt/tau) on each
// genuinely new frame, composites it between a fresh backdrop and a fresh
// overlay, and — when the framebuffers can't be created — releases and returns
// false so the caller falls back to a direct render (ScopePhosphor.java:142-149,
// ensureBuffers :356-395). We mirror that exactly.
//
// WebGL2 is required because Canvas2D is 8-bit: an 8-bit accumulation buffer has
// an integer decay floor that leaves a faint permanent ghost (Java documents
// this at ScopePhosphor.java:397-399). RGBA16F (half-float via
// EXT_color_buffer_float) decays cleanly to zero. If WebGL2, the float-colour
// extension, or FBO completeness is unavailable, the engine reports unsupported
// and every render() returns false — the web mirror of Java's release() -> false.
//
// This module deliberately owns NO knowledge of preferences or the render loop:
// the pure, node-testable PhosphorGate below decides WHICH Kind each frame is
// (trigger-source change -> CLEAR, geometry change -> RESET, else the caller's
// kind), and phosphorFade() is the decay arithmetic as a pure function. The GL
// engine only executes a Kind. scope-view integration is a separate agent's job.

/**
 * Upper bound on a single decay step's `dt` (seconds), so resuming after a long
 * pause — the realtime loop was idle, so the decay clock didn't advance — fades
 * the afterglow gently over a few frames instead of wiping it in one.
 * Java MAX_DECAY_SECONDS (ScopePhosphor.java:63-66).
 * @type {number}
 */
export const MAX_DECAY_SECONDS = 0.5;

/**
 * The frame-rate-independent phosphor decay fraction for one step: the amount of
 * the existing afterglow that fades away this frame. Faithful to Java accumulate()
 * (ScopePhosphor.java:260-263): `fade = 1 - exp(-dt/tau)`, with `dt` clamped to
 * {@link MAX_DECAY_SECONDS}. The surviving fraction the buffer is multiplied by is
 * therefore `1 - fade = exp(-dt/tau)`.
 *
 * Pure function (no GL) so the decay math is unit-testable in node.
 *
 * @param {number} dtSeconds elapsed time since the last accumulate, in seconds
 *                 (raw; this function applies the {@link MAX_DECAY_SECONDS} cap).
 * @param {number} tauSeconds persistence time constant (must be > 0 for a finite
 *                 decay). For infinite persistence (tau < 0) the caller must skip
 *                 decay entirely — see PhosphorGate / the engine; passing tau <= 0
 *                 here is a programming error and returns 0 (no fade).
 * @returns {number} fade fraction in [0, 1]
 */
export function phosphorFade(dtSeconds, tauSeconds) {
  if (!(tauSeconds > 0)) return 0.0;
  const dt = Math.min(dtSeconds, MAX_DECAY_SECONDS);
  return 1.0 - Math.exp(-dt / tauSeconds);
}

// --- Fullscreen-quad shaders: sample the scratch texture into the current FBO.
// Two verts triangles covering clip space; the fragment stage just reads RGBA
// from the source texture. Premultiplied source-over / decay is done entirely
// with fixed-function blending (blendFunc), exactly like NanoVG's composite
// blend funcs in the Java path — the shader never premultiplies.
const QUAD_VERT = `#version 300 es
in vec2 a_pos;
out vec2 v_uv;
void main() {
  v_uv = a_pos * 0.5 + 0.5;
  gl_Position = vec4(a_pos, 0.0, 1.0);
}`;

const QUAD_FRAG = `#version 300 es
precision highp float;
uniform sampler2D u_tex;
in vec2 v_uv;
out vec4 fragColor;
void main() { fragColor = texture(u_tex, v_uv); }`;

// A flat-colour fill for the decay pass (the black quad multiplied in with
// blendFunc(ZERO, ONE_MINUS_SRC_ALPHA) — Java decay(), ScopePhosphor.java:329-340).
const FILL_FRAG = `#version 300 es
precision highp float;
uniform vec4 u_color;
out vec4 fragColor;
void main() { fragColor = u_color; }`;

/**
 * GPU display persistence ("digital phosphor") for the oscilloscope — a faithful
 * structural port of org.edgo.audio.measure.gui.scope.gl.ScopePhosphor.
 *
 * <p>The engine owns a hidden WebGL2 canvas and two RGBA16F texture/FBO pairs:
 * <ul>
 *   <li>the <b>scratch</b> buffer holds just the current frame's trace (uploaded
 *       from the caller's 2D trace canvas);</li>
 *   <li>the <b>phosphor</b> buffer is the decayed accumulation. On each genuinely
 *       new captured frame it is multiplied down by `exp(-dt/tau)` and the scratch
 *       trace is composited in (REALTIME); a held frame leaves it (COMPOSITE); a
 *       geometry gesture resets it (RESET); a signal-affecting change wipes it
 *       without re-stamping (CLEAR). An empty buffer (first frame / just resized)
 *       is seeded from the current trace so the persisted layer is never blank —
 *       vital for a STOPPED scope (Java :213-219).</li>
 * </ul>
 *
 * <p><b>Caller contract (compositing order — Java compositeToScreen :313-324).</b>
 * The engine renders ONLY the persisted trace onto its own transparent WebGL
 * canvas and returns true. The caller draws, in order:
 * <ol>
 *   <li>the fresh BACKDROP (background fill + graticule) to the screen canvas;</li>
 *   <li>this engine's canvas via {@link ScopePhosphor#canvas} (drawImage);</li>
 *   <li>the fresh OVERLAY (sliders, labels, measurement table, header).</li>
 * </ol>
 * The Phase names (BACKDROP / TRACE / OVERLAY) are from
 * org.edgo.audio.measure.gui.scope.gl.GlScopeRenderer.Phase: BACKDROP never
 * persists, TRACE is the layer that accumulates in the phosphor buffer, OVERLAY
 * is drawn fresh on top. The `traceCanvas` this engine consumes is the app's
 * TRACE render; the caller supplies BACKDROP and OVERLAY itself.
 *
 * <p>When persistence is off, unsupported, or the buffers can't be created, every
 * render() returns false (having released) and the caller must direct-render the
 * whole frame itself — the web mirror of Java's release() -> false fallback.
 */
export class ScopePhosphor {

  /**
   * Which kind of frame is being rendered — selects the persistence behaviour.
   * Semantics EXACTLY Java ScopePhosphor.Kind (:77-90).
   * @readonly @enum {string}
   */
  static Kind = Object.freeze({
    /** Realtime loop: decay + accumulate the trace, but only if it's genuinely new. */
    REALTIME: 'REALTIME',
    /** UI gesture / geometry change: re-render the trace and reset the afterglow. */
    RESET: 'RESET',
    /** Expose / resize: re-composite the frozen phosphor (no decay, no accumulate). */
    COMPOSITE: 'COMPOSITE',
    /** Signal-affecting change (trigger source/type/edge, generator): wipe the
     *  afterglow WITHOUT re-stamping — the current trace is still anchored on the
     *  pre-change event; stay blank until the next genuinely new frame. */
    CLEAR: 'CLEAR',
  });

  /** Lazy: nothing is allocated until the first {@link ScopePhosphor#attach}. */
  constructor() {
    /** Hidden WebGL2 canvas the engine composites the persisted trace onto; the
     *  caller drawImage()s this between backdrop and overlay. @type {?HTMLCanvasElement} */
    this.canvas = null;
    /** @type {?WebGL2RenderingContext} */
    this._gl = null;
    /** True once WebGL2 + float-colour + complete FBOs are confirmed unusable, so
     *  every render() short-circuits to false (Java ensureBuffers failure). */
    this._unsupported = false;

    // GL objects (all null until attach()).
    this._scratchTex = null;
    this._scratchFbo = null;
    this._phosphorTex = null;
    this._phosphorFbo = null;
    this._quadProg = null;
    this._fillProg = null;
    this._quadVbo = null;
    this._quadVao = null;

    /** Allocated buffer size, in device pixels. */
    this._w = 0;
    this._h = 0;

    // Decay-clock state — Java lastAccumNanos / haveLastAccum (:105-106).
    /** ms timestamp of the last accumulate/reset/clear; base for the next dt. */
    this._lastAccumMs = 0;
    /** false until the first accumulate / after a reset; gates decay + seeding. */
    this._haveLastAccum = false;

    /** Set by {@link ScopePhosphor#clearPersistence} (volatile-style); consumed as
     *  a CLEAR on the next render(), taking priority over the caller's kind — Java
     *  clearRequested (:107-109) + its OR into CLEAR (:156, :186). */
    this._clearRequested = false;
  }

  /**
   * (Re)allocates the hidden canvas + WebGL2 context + both RGBA16F texture/FBO
   * pairs whenever the size changes. On the first call it creates the WebGL2
   * context and requires EXT_color_buffer_float (half-float RGBA16F is fine). If
   * WebGL2, the extension, or FBO completeness is unavailable the instance marks
   * itself unsupported and every subsequent render() returns false — the web
   * mirror of Java ensureBuffers() -> release() -> false (ScopePhosphor.java:356-395).
   *
   * @param {number} width  target width in device pixels
   * @param {number} height target height in device pixels
   * @returns {boolean} true if the engine is ready at this size; false if unsupported
   */
  attach(width, height) {
    const w = Math.max(1, width | 0);
    const h = Math.max(1, height | 0);
    if (this._unsupported) return false;
    if (this._gl && this._w === w && this._h === h) return true;

    if (!this._gl && !this._initContext()) return false;   // sets _unsupported on failure
    const gl = this._gl;

    // Size the hidden canvas so drawImage() gives the caller the full-res result.
    this.canvas.width = w;
    this.canvas.height = h;

    this._releaseBuffers();
    this._w = w;
    this._h = h;

    this._scratchTex = this._newFloatTexture(w, h);
    this._scratchFbo = this._newFbo(this._scratchTex);
    this._phosphorTex = this._newFloatTexture(w, h);
    this._phosphorFbo = this._newFbo(this._phosphorTex);

    const ok = this._scratchTex && this._scratchFbo
      && this._phosphorTex && this._phosphorFbo;
    if (!ok) {
      this._releaseBuffers();
      this._unsupported = true;
      return false;
    }

    // Start the accumulation transparent — Java ensureBuffers clears the phosphor
    // FBO on (re)allocation (ScopePhosphor.java:380-381).
    this._clearFbo(this._phosphorFbo);
    // A resize discards the afterglow — force the seeding path next frame.
    this._haveLastAccum = false;
    return true;
  }

  /**
   * Renders one persisted frame onto the engine's canvas and returns true, or
   * releases and returns false when persistence is off / unsupported. Does NOT
   * touch the screen — the CALLER drawImage()s {@link ScopePhosphor#canvas}
   * between backdrop and overlay (see the class-level compositing contract).
   * Faithful to Java render() (ScopePhosphor.java:144-222); the trigger/geometry
   * kind-override that Java does inline (:151-187) lives in {@link PhosphorGate}
   * instead — pass the already-decided `kind` here.
   *
   * @param {string} kind one of {@link ScopePhosphor.Kind}
   * @param {CanvasImageSource} traceCanvas the app's fresh TRACE render (the
   *        waveforms only, on a transparent background) to upload as scratch
   * @param {number} persistSeconds resolved persistence: 0 = off, < 0 = infinite,
   *        > 0 = decay time constant (tau) in seconds — Java persistenceSeconds().
   * @param {boolean} frameIsNew whether this is a genuinely new captured frame —
   *        Java renderer.isLastFrameNew(); only REALTIME consults it.
   * @param {number} nowMs current time in ms (e.g. performance.now()); the decay
   *        clock base. Java uses System.nanoTime() internally per call.
   * @returns {boolean} true if the engine rendered (canvas is ready to composite);
   *          false if the caller must direct-render the whole frame itself
   */
  render(kind, traceCanvas, persistSeconds, frameIsNew, nowMs) {
    // Persistence off — Java render() :147-150.
    if (persistSeconds === 0) {
      this.release();
      return false;
    }
    if (this._unsupported) return false;
    if (!this.attach(this.canvas ? this.canvas.width : this._w,
                     this.canvas ? this.canvas.height : this._h)) {
      // If never attached, there is nothing to size against — treat as unsupported
      // fallback. (Callers normally attach() first; this guards a bare render().)
      return false;
    }
    if (!this._gl || !this._phosphorFbo) return false;

    // External clear (generator change etc.) forces CLEAR next frame, taking
    // priority — Java :156-157 + :186. clearRequested is consumed here.
    let effKind = kind;
    if (this._clearRequested) {
      this._clearRequested = false;
      effKind = ScopePhosphor.Kind.CLEAR;
    }

    let traced = false;
    switch (effKind) {
      case ScopePhosphor.Kind.REALTIME:
        this._uploadTrace(traceCanvas);         // scratch <- current trace
        traced = true;
        if (frameIsNew) this._accumulate(persistSeconds, nowMs);
        break;
      case ScopePhosphor.Kind.RESET:
        this._uploadTrace(traceCanvas);
        traced = true;
        this._resetPhosphor(nowMs);
        break;
      case ScopePhosphor.Kind.COMPOSITE:
        // Phosphor frozen — just re-composite below.
        break;
      case ScopePhosphor.Kind.CLEAR:
        this._clearPhosphor(nowMs);
        break;
      default:
        break;
    }

    // Seed an empty phosphor (first frame, or a resize just reallocated + cleared
    // it) from the current trace, so the persisted layer is never blank — vital
    // for a STOPPED scope whose only repaint is a COMPOSITE expose/resize
    // (Java :213-219).
    if (!this._haveLastAccum) {
      if (!traced) this._uploadTrace(traceCanvas);
      this._resetPhosphor(nowMs);
    }

    this._compositeToCanvas();
    return true;
  }

  /**
   * Requests a {@link ScopePhosphor.Kind.CLEAR} on the next rendered frame —
   * called (from any code path, e.g. a USER generator change arriving over the
   * bus) when non-render code invalidates the afterglow. Consumed as CLEAR by the
   * next render(), taking priority over the caller's kind and over RESET. Java
   * clearPersistence() (ScopePhosphor.java:288-293).
   */
  clearPersistence() {
    this._clearRequested = true;
  }

  /**
   * Frees the GL objects and marks the buffer not-accumulated. Java release()
   * (ScopePhosphor.java:224-236). The engine can be re-{@link ScopePhosphor#attach}ed
   * afterwards (unless it was marked unsupported).
   */
  release() {
    this._releaseBuffers();
    if (this._gl) {
      const gl = this._gl;
      if (this._quadProg) { gl.deleteProgram(this._quadProg); this._quadProg = null; }
      if (this._fillProg) { gl.deleteProgram(this._fillProg); this._fillProg = null; }
      if (this._quadVbo) { gl.deleteBuffer(this._quadVbo); this._quadVbo = null; }
      if (this._quadVao) { gl.deleteVertexArray(this._quadVao); this._quadVao = null; }
    }
    this._w = 0;
    this._h = 0;
    this._haveLastAccum = false;
  }

  // ---- internals -----------------------------------------------------------

  /** Creates the hidden canvas + WebGL2 context + float-colour extension + the
   *  quad geometry & shader programs. Marks unsupported (returns false) if any
   *  step fails. Java's context/extension checks are implicit in ensureBuffers. */
  _initContext() {
    let canvas;
    try {
      canvas = (typeof document !== 'undefined' && document.createElement)
        ? document.createElement('canvas')
        : null;
    } catch { canvas = null; }
    const gl = canvas && canvas.getContext
      ? canvas.getContext('webgl2', { premultipliedAlpha: true, antialias: false })
      : null;
    if (!gl) { this._unsupported = true; return false; }
    // RGBA16F as a colour-renderable FBO attachment requires this extension.
    if (!gl.getExtension('EXT_color_buffer_float')) {
      this._unsupported = true;
      return false;
    }
    this.canvas = canvas;
    this._gl = gl;

    this._quadProg = this._buildProgram(QUAD_VERT, QUAD_FRAG);
    this._fillProg = this._buildProgram(QUAD_VERT, FILL_FRAG);
    if (!this._quadProg || !this._fillProg) {
      this._unsupported = true;
      return false;
    }

    // A single triangle-strip quad covering clip space, shared by both programs
    // (both bind a_pos at location 0).
    this._quadVbo = gl.createBuffer();
    gl.bindBuffer(gl.ARRAY_BUFFER, this._quadVbo);
    gl.bufferData(gl.ARRAY_BUFFER,
      new Float32Array([-1, -1, 1, -1, -1, 1, 1, 1]), gl.STATIC_DRAW);
    this._quadVao = gl.createVertexArray();
    gl.bindVertexArray(this._quadVao);
    gl.enableVertexAttribArray(0);
    gl.vertexAttribPointer(0, 2, gl.FLOAT, false, 0, 0);
    gl.bindVertexArray(null);
    return true;
  }

  /** Compiles + links a program with a_pos bound to location 0. Returns null on
   *  any compile/link error (caller marks unsupported). */
  _buildProgram(vertSrc, fragSrc) {
    const gl = this._gl;
    const vs = this._compileShader(gl.VERTEX_SHADER, vertSrc);
    const fs = this._compileShader(gl.FRAGMENT_SHADER, fragSrc);
    if (!vs || !fs) return null;
    const prog = gl.createProgram();
    gl.attachShader(prog, vs);
    gl.attachShader(prog, fs);
    gl.bindAttribLocation(prog, 0, 'a_pos');
    gl.linkProgram(prog);
    gl.deleteShader(vs);
    gl.deleteShader(fs);
    if (!gl.getProgramParameter(prog, gl.LINK_STATUS)) {
      gl.deleteProgram(prog);
      return null;
    }
    return prog;
  }

  _compileShader(type, src) {
    const gl = this._gl;
    const sh = gl.createShader(type);
    gl.shaderSource(sh, src);
    gl.compileShader(sh);
    if (!gl.getShaderParameter(sh, gl.COMPILE_STATUS)) {
      gl.deleteShader(sh);
      return null;
    }
    return sh;
  }

  /** Allocates a device-size RGBA16F texture (float colour — an 8-bit buffer has
   *  an integer decay floor that leaves a faint permanent ghost; RGBA16F decays
   *  cleanly to zero). Java newFloatTexture() (ScopePhosphor.java:397-408). */
  _newFloatTexture(w, h) {
    const gl = this._gl;
    const tex = gl.createTexture();
    gl.bindTexture(gl.TEXTURE_2D, tex);
    gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA16F, w, h, 0, gl.RGBA, gl.HALF_FLOAT, null);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
    gl.bindTexture(gl.TEXTURE_2D, null);
    return tex;
  }

  /** Wraps a colour texture in a framebuffer; returns null if incomplete (Java
   *  glCheckFramebufferStatus != GL_FRAMEBUFFER_COMPLETE, :371/:379). The phosphor
   *  buffer needs no depth/stencil (convex fills + blits only); the trace is
   *  rasterised on the caller's 2D canvas, so scratch needs none either here. */
  _newFbo(tex) {
    const gl = this._gl;
    const fbo = gl.createFramebuffer();
    gl.bindFramebuffer(gl.FRAMEBUFFER, fbo);
    gl.framebufferTexture2D(gl.FRAMEBUFFER, gl.COLOR_ATTACHMENT0, gl.TEXTURE_2D, tex, 0);
    const complete = gl.checkFramebufferStatus(gl.FRAMEBUFFER) === gl.FRAMEBUFFER_COMPLETE;
    gl.bindFramebuffer(gl.FRAMEBUFFER, null);
    if (!complete) { gl.deleteFramebuffer(fbo); return null; }
    return fbo;
  }

  /** Uploads the caller's 2D trace canvas into the scratch texture (premultiplied
   *  to match the premultiplied source-over blend used when compositing). Java
   *  renderTraceToScratch() (ScopePhosphor.java:238-250) rasterises the trace into
   *  the scratch FBO; here the trace is already rasterised on the 2D canvas, so we
   *  only upload it. */
  _uploadTrace(traceCanvas) {
    const gl = this._gl;
    gl.bindTexture(gl.TEXTURE_2D, this._scratchTex);
    gl.pixelStorei(gl.UNPACK_PREMULTIPLY_ALPHA_WEBGL, true);
    gl.pixelStorei(gl.UNPACK_FLIP_Y_WEBGL, true);
    gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA16F, gl.RGBA, gl.HALF_FLOAT, traceCanvas);
    gl.pixelStorei(gl.UNPACK_PREMULTIPLY_ALPHA_WEBGL, false);
    gl.pixelStorei(gl.UNPACK_FLIP_Y_WEBGL, false);
    gl.bindTexture(gl.TEXTURE_2D, null);
  }

  /** New captured frame: decay the phosphor by exp(-dt/tau) (skipped for the first
   *  frame and for infinite persistence) then composite the scratch trace into it.
   *  Java accumulate() (ScopePhosphor.java:252-271). */
  _accumulate(persistSeconds, nowMs) {
    const gl = this._gl;
    gl.bindFramebuffer(gl.FRAMEBUFFER, this._phosphorFbo);
    gl.viewport(0, 0, this._w, this._h);
    gl.enable(gl.BLEND);

    if (persistSeconds > 0 && this._haveLastAccum) {
      const dt = (nowMs - this._lastAccumMs) / 1000;
      const fade = phosphorFade(dt, persistSeconds);
      this._decay(fade);
    }
    // (Infinite persistence, persistSeconds < 0: never decay — just accumulate.)
    this._compositeScratch();

    gl.bindFramebuffer(gl.FRAMEBUFFER, null);
    this._lastAccumMs = nowMs;
    this._haveLastAccum = true;
  }

  /** Multiplies the bound buffer by (1 - fade) = exp(-dt/tau) on every channel:
   *  a (0,0,0,fade) quad with blendFunc(ZERO, ONE_MINUS_SRC_ALPHA) gives
   *  `dst = 0 + dst*(1 - fade)`. Java decay() (ScopePhosphor.java:326-340). The
   *  phosphor FBO must already be bound. */
  _decay(fade) {
    const gl = this._gl;
    gl.useProgram(this._fillProg);
    gl.uniform4f(gl.getUniformLocation(this._fillProg, 'u_color'), 0, 0, 0, fade);
    gl.blendFunc(gl.ZERO, gl.ONE_MINUS_SRC_ALPHA);
    gl.bindVertexArray(this._quadVao);
    gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4);
    gl.bindVertexArray(null);
  }

  /** Merges the scratch texture into the bound buffer BRIGHTEST-WINS (per-component
   *  max, blendEquation(MAX) — core in WebGL2; the blend factors are ignored under
   *  MAX). Source-over re-composited the trace's anti-aliased fringe pixels over
   *  themselves on every new frame, converging them to full opacity — the persisted
   *  trace turned solid-edged and fat. Under max a fringe pixel can never exceed its
   *  single-frame coverage, so the persisted trace keeps exactly the anti-aliasing
   *  of a persistence-off frame while decayed history fades underneath. Java
   *  accumulate()'s GL_MAX-bracketed deposit blit (ScopePhosphor.java). The target
   *  FBO must already be bound; the equation is restored to FUNC_ADD for the decay
   *  quad and every later pass. */
  _compositeScratch() {
    const gl = this._gl;
    gl.useProgram(this._quadProg);
    gl.uniform1i(gl.getUniformLocation(this._quadProg, 'u_tex'), 0);
    gl.activeTexture(gl.TEXTURE0);
    gl.bindTexture(gl.TEXTURE_2D, this._scratchTex);
    gl.blendEquation(gl.MAX);
    gl.bindVertexArray(this._quadVao);
    gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4);
    gl.bindVertexArray(null);
    gl.bindTexture(gl.TEXTURE_2D, null);
    gl.blendEquation(gl.FUNC_ADD);
  }

  /** Signal-affecting change: wipe the afterglow to transparent WITHOUT stamping
   *  the current trace — it is still anchored on the pre-change event, and with
   *  rare (glitch) triggers the decay would keep it visible for minutes. The
   *  buffer counts as valid (haveLastAccum = true) so the seeding path doesn't
   *  immediately re-stamp; the next genuinely new frame starts accumulation fresh.
   *  Java clearPhosphor() (ScopePhosphor.java:273-286). */
  _clearPhosphor(nowMs) {
    this._clearFbo(this._phosphorFbo);
    this._lastAccumMs = nowMs;
    this._haveLastAccum = true;
  }

  /** UI gesture / seed: wipe the (possibly stale-coordinate) afterglow and stamp
   *  the freshly-uploaded scratch trace, so the trace tracks the gesture instead
   *  of smearing. The reset is itself a phosphor update: the buffer now holds valid
   *  content and the decay clock starts here. Java resetPhosphor() (:295-311). */
  _resetPhosphor(nowMs) {
    const gl = this._gl;
    gl.bindFramebuffer(gl.FRAMEBUFFER, this._phosphorFbo);
    gl.viewport(0, 0, this._w, this._h);
    gl.clearColor(0, 0, 0, 0);
    gl.clear(gl.COLOR_BUFFER_BIT);
    gl.enable(gl.BLEND);
    this._compositeScratch();
    gl.bindFramebuffer(gl.FRAMEBUFFER, null);
    this._lastAccumMs = nowMs;
    this._haveLastAccum = true;
  }

  /** Draws the phosphor texture onto the default framebuffer (the hidden canvas)
   *  over a transparent background, so the caller drawImage()s it between backdrop
   *  and overlay. Java compositeToScreen() (ScopePhosphor.java:313-324) draws
   *  BACKDROP + phosphor + OVERLAY into one frame; the web split renders BACKDROP
   *  and OVERLAY on the caller's 2D canvas, so here we emit ONLY the phosphor. */
  _compositeToCanvas() {
    const gl = this._gl;
    gl.bindFramebuffer(gl.FRAMEBUFFER, null);
    gl.viewport(0, 0, this._w, this._h);
    gl.clearColor(0, 0, 0, 0);      // transparent — the backdrop is on the 2D canvas
    gl.clear(gl.COLOR_BUFFER_BIT);
    gl.enable(gl.BLEND);
    gl.useProgram(this._quadProg);
    gl.uniform1i(gl.getUniformLocation(this._quadProg, 'u_tex'), 0);
    gl.activeTexture(gl.TEXTURE0);
    gl.bindTexture(gl.TEXTURE_2D, this._phosphorTex);
    gl.blendFunc(gl.ONE, gl.ONE_MINUS_SRC_ALPHA);   // premultiplied source-over
    gl.bindVertexArray(this._quadVao);
    gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4);
    gl.bindVertexArray(null);
    gl.bindTexture(gl.TEXTURE_2D, null);
  }

  /** Clears one FBO to transparent black. */
  _clearFbo(fbo) {
    const gl = this._gl;
    gl.bindFramebuffer(gl.FRAMEBUFFER, fbo);
    gl.viewport(0, 0, this._w, this._h);
    gl.clearColor(0, 0, 0, 0);
    gl.clear(gl.COLOR_BUFFER_BIT);
    gl.bindFramebuffer(gl.FRAMEBUFFER, null);
  }

  /** Frees just the size-dependent textures + framebuffers (not the shared
   *  programs / geometry). Java release() frees FBOs/textures/images (:224-236);
   *  the web keeps the programs so a resize only re-allocates the buffers. */
  _releaseBuffers() {
    const gl = this._gl;
    if (!gl) return;
    if (this._scratchFbo) { gl.deleteFramebuffer(this._scratchFbo); this._scratchFbo = null; }
    if (this._phosphorFbo) { gl.deleteFramebuffer(this._phosphorFbo); this._phosphorFbo = null; }
    if (this._scratchTex) { gl.deleteTexture(this._scratchTex); this._scratchTex = null; }
    if (this._phosphorTex) { gl.deleteTexture(this._phosphorTex); this._phosphorTex = null; }
    this._haveLastAccum = false;
  }
}

/**
 * The per-frame kind-decision state machine extracted from Java
 * ScopePhosphor.render() (ScopePhosphor.java:151-187) — a pure, node-testable
 * class with NO GL and NO preferences dependency. Given the caller's requested
 * Kind and the current watched settings, it decides the EFFECTIVE Kind:
 *
 * <ul>
 *   <li>first observation (prefsSeen false): pass the caller's kind through — no
 *       CLEAR/RESET override yet (Java gates every comparison on prefsSeen);</li>
 *   <li>external clear request OR a trigger-source change (mode/type/edge/channel)
 *       -> CLEAR (wipe without re-stamping; extClear takes priority);</li>
 *   <li>else a geometry change (timePerDiv / per-channel volts-per-div / offsets /
 *       trigger position) -> RESET (wipe + re-stamp);</li>
 *   <li>else pass the caller's kind through unchanged.</li>
 * </ul>
 *
 * <p>Java resolves the effective persistence seconds from the OscPersistenceMode
 * enum (persistenceSeconds(), :410-413). The web PersistenceMode module (Agent A)
 * did not exist when this was written, so the gate takes `persistSeconds` as a
 * plain number and passes it straight through. If/when persistence-mode.js lands,
 * a caller can resolve it before {@link PhosphorGate#decide} — the gate itself
 * only needs the resolved number.
 */
export class PhosphorGate {

  constructor() {
    // prefsSeen gates the first frame — Java prefsSeen (:113).
    this._prefsSeen = false;
    // Settings the current afterglow was accumulated under — Java lastTrigger* /
    // lastTimePerDiv / ... (:114-123).
    this._lastTriggerMode = null;
    this._lastTriggerType = null;
    this._lastTriggerEdge = null;
    this._lastTriggerChannel = null;
    this._lastTimePerDiv = 0;
    this._lastLeftVdiv = 0;
    this._lastRightVdiv = 0;
    this._lastLeftOff = 0;
    this._lastRightOff = 0;
    this._lastTriggerPos = 0;
  }

  /**
   * Decides the effective Kind for this frame and returns it alongside the
   * resolved persistence seconds. Records the watched settings for next frame's
   * comparison (Java :175-185). Faithful to Java render() :151-187: extClear /
   * trigger-source change -> CLEAR (extClear priority); else geometry change ->
   * RESET; else the caller's kind. The first observation records the baseline and
   * passes the kind through with no override.
   *
   * @param {string} kind the caller's requested {@link ScopePhosphor.Kind}
   * @param {object} inputs watched per-frame state:
   * @param {string} inputs.triggerMode  serialised TriggerMode (AUTO/NORMAL/SINGLE)
   * @param {string} inputs.triggerType  serialised TriggerType (EDGE/GLITCH)
   * @param {string} inputs.triggerEdge  serialised TriggerEdge (RISE/FALL)
   * @param {string} inputs.triggerChannel serialised trigger Channel
   * @param {number} inputs.timePerDiv   seconds/division
   * @param {number} inputs.leftVdiv     left volts/division
   * @param {number} inputs.rightVdiv    right volts/division
   * @param {number} inputs.leftOff      left vertical offset fraction
   * @param {number} inputs.rightOff     right vertical offset fraction
   * @param {number} inputs.triggerPos   trigger position fraction
   * @param {boolean} inputs.extClearRequested external clear flag (generator etc.)
   * @param {number} persistSeconds resolved persistence: 0 = off, < 0 = infinite,
   *        > 0 = decay tau seconds. Passed through unchanged (see class note).
   * @returns {{kind: string, persistSeconds: number}}
   */
  decide(kind, inputs, persistSeconds) {
    const extClear = !!inputs.extClearRequested;

    const triggerChanged = this._prefsSeen && (
      inputs.triggerMode !== this._lastTriggerMode
      || inputs.triggerType !== this._lastTriggerType
      || inputs.triggerEdge !== this._lastTriggerEdge
      || inputs.triggerChannel !== this._lastTriggerChannel);
    const geometryChanged = this._prefsSeen && (
      inputs.timePerDiv !== this._lastTimePerDiv
      || inputs.leftVdiv !== this._lastLeftVdiv
      || inputs.rightVdiv !== this._lastRightVdiv
      || inputs.leftOff !== this._lastLeftOff
      || inputs.rightOff !== this._lastRightOff
      || inputs.triggerPos !== this._lastTriggerPos);

    this._lastTriggerMode = inputs.triggerMode;
    this._lastTriggerType = inputs.triggerType;
    this._lastTriggerEdge = inputs.triggerEdge;
    this._lastTriggerChannel = inputs.triggerChannel;
    this._lastTimePerDiv = inputs.timePerDiv;
    this._lastLeftVdiv = inputs.leftVdiv;
    this._lastRightVdiv = inputs.rightVdiv;
    this._lastLeftOff = inputs.leftOff;
    this._lastRightOff = inputs.rightOff;
    this._lastTriggerPos = inputs.triggerPos;
    this._prefsSeen = true;

    let effKind = kind;
    if (extClear || triggerChanged) effKind = ScopePhosphor.Kind.CLEAR;
    else if (geometryChanged)       effKind = ScopePhosphor.Kind.RESET;
    return { kind: effKind, persistSeconds };
  }
}
