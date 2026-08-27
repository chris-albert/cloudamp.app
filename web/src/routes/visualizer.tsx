import { useEffect, useRef, useState, useCallback, useSyncExternalStore } from "react";
import { getAnalyserNode, getPlayerState, subscribePlayerState, getCurrentTrack, startMicAnalyser, stopMicAnalyser } from "@/lib/player-store";

type VisMode = "eq" | "oscilloscope" | "radial" | "tunnel" | "kaleidoscope" | "warpgrid" | "honeycomb" | "diamond" | "starburst" | "spiral" | "liquid" | "fractal" | "blend";

const MODES: { id: VisMode; label: string }[] = [
  { id: "eq", label: "EQ Bars" },
  { id: "oscilloscope", label: "Oscilloscope" },
  { id: "radial", label: "Radial" },
  { id: "tunnel", label: "Tunnel" },
  { id: "kaleidoscope", label: "Kaleidoscope" },
  { id: "warpgrid", label: "Warp Grid" },
  { id: "honeycomb", label: "Honeycomb" },
  { id: "diamond", label: "Diamond" },
  { id: "starburst", label: "Starburst" },
  { id: "spiral", label: "Spiral" },
  { id: "liquid", label: "Liquid" },
  { id: "fractal", label: "Fractal" },
  { id: "blend", label: "Blend" },
];

// Blend mode cycles through every other mode, crossfading between them
const BLEND_CYCLE: VisMode[] = MODES.map((m) => m.id).filter((id) => id !== "blend");
const BLEND_INTERVAL_MS = 30_000;
const BLEND_FADE_MS = 2000;

export function VisualizerPage() {
  useSyncExternalStore(subscribePlayerState, getPlayerState);
  const currentTrack = getCurrentTrack();

  const [mode, setMode] = useState<VisMode>(
    () => (localStorage.getItem("cloudamp_vis_mode") as VisMode) || "eq",
  );
  const [micActive, setMicActive] = useState(false);
  const [micAnalyser, setMicAnalyser] = useState<AnalyserNode | null>(null);
  const [isFullscreen, setIsFullscreen] = useState(false);
  const stageRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const onChange = () => setIsFullscreen(document.fullscreenElement === stageRef.current);
    document.addEventListener("fullscreenchange", onChange);
    return () => document.removeEventListener("fullscreenchange", onChange);
  }, []);

  function toggleFullscreen() {
    if (document.fullscreenElement) {
      document.exitFullscreen();
    } else {
      stageRef.current?.requestFullscreen().catch((err) => console.error("Fullscreen failed:", err));
    }
  }

  function selectMode(m: VisMode) {
    setMode(m);
    localStorage.setItem("cloudamp_vis_mode", m);
  }

  async function toggleMic() {
    if (micActive) {
      stopMicAnalyser();
      setMicAnalyser(null);
      setMicActive(false);
    } else {
      try {
        const analyser = await startMicAnalyser();
        setMicAnalyser(analyser);
        setMicActive(true);
      } catch (err) {
        console.error("Microphone access denied:", err);
      }
    }
  }

  // Clean up mic on unmount
  useEffect(() => {
    return () => {
      if (micActive) {
        stopMicAnalyser();
      }
    };
  }, [micActive]);

  const hasSource = currentTrack || micActive;

  return (
    <div className="space-y-4 flex flex-col" style={{ minHeight: "calc(100vh - 10rem)" }}>
      {/* Mode selector */}
      <div className="flex flex-wrap items-center gap-1">
        {MODES.map((m) => (
          <button
            key={m.id}
            onClick={() => selectMode(m.id)}
            className={`px-3 py-1.5 rounded-md text-xs font-medium transition-colors ${
              mode === m.id
                ? "bg-white text-black"
                : "bg-zinc-800 text-zinc-400 hover:text-white hover:bg-zinc-700"
            }`}
          >
            {m.label}
          </button>
        ))}
        <div className="w-px h-6 bg-zinc-700 mx-1" />
        <button
          onClick={toggleMic}
          className={`px-3 py-1.5 rounded-md text-xs font-medium transition-colors flex items-center gap-1.5 ${
            micActive
              ? "bg-red-600 text-white hover:bg-red-700"
              : "bg-zinc-800 text-zinc-400 hover:text-white hover:bg-zinc-700"
          }`}
        >
          <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
            <path d="M12 1a3 3 0 0 0-3 3v8a3 3 0 0 0 6 0V4a3 3 0 0 0-3-3z" />
            <path d="M19 10v2a7 7 0 0 1-14 0v-2" />
            <line x1="12" y1="19" x2="12" y2="23" />
            <line x1="8" y1="23" x2="16" y2="23" />
          </svg>
          Mic
        </button>
        <button
          onClick={toggleFullscreen}
          title="Fullscreen"
          className="px-3 py-1.5 rounded-md text-xs font-medium transition-colors flex items-center gap-1.5 bg-zinc-800 text-zinc-400 hover:text-white hover:bg-zinc-700"
        >
          <FullscreenIcon exit={false} />
          Fullscreen
        </button>
      </div>

      {/* Canvas area */}
      <div ref={stageRef} className="flex-1 rounded-lg border border-zinc-800 bg-black overflow-hidden relative min-h-[400px] group">
        {isFullscreen && (
          <button
            onClick={toggleFullscreen}
            title="Exit fullscreen"
            className="absolute top-3 right-3 z-10 p-2 rounded-md bg-zinc-900/70 text-zinc-400 hover:text-white opacity-0 group-hover:opacity-100 transition-opacity"
          >
            <FullscreenIcon exit />
          </button>
        )}
        {!hasSource ? (
          <div className="absolute inset-0 flex items-center justify-center">
            <div className="text-center space-y-2">
              <div className="text-zinc-600 text-4xl">♪</div>
              <div className="text-sm text-zinc-500">Play something or enable mic to see visualizations</div>
            </div>
          </div>
        ) : mode === "blend" ? (
          <BlendVisualizer micAnalyser={micActive ? micAnalyser : null} />
        ) : (
          <VisualizerCanvas mode={mode} micAnalyser={micActive ? micAnalyser : null} />
        )}
      </div>
    </div>
  );
}

function FullscreenIcon({ exit }: { exit: boolean }) {
  return (
    <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      {exit ? (
        <>
          <path d="M8 3v3a2 2 0 0 1-2 2H3" />
          <path d="M21 8h-3a2 2 0 0 1-2-2V3" />
          <path d="M3 16h3a2 2 0 0 1 2 2v3" />
          <path d="M16 21v-3a2 2 0 0 1 2-2h3" />
        </>
      ) : (
        <>
          <path d="M8 3H5a2 2 0 0 0-2 2v3" />
          <path d="M21 8V5a2 2 0 0 0-2-2h-3" />
          <path d="M3 16v3a2 2 0 0 0 2 2h3" />
          <path d="M16 21h3a2 2 0 0 0 2-2v-3" />
        </>
      )}
    </svg>
  );
}

// ── Blend mode: cycle through all modes with a crossfade ─────────────────

function BlendVisualizer({ micAnalyser }: { micAnalyser: AnalyserNode | null }) {
  // Up to two stacked layers: the outgoing one underneath, the incoming one fading in on top
  const [layers, setLayers] = useState<{ key: number; mode: VisMode }[]>(() => [{ key: 0, mode: BLEND_CYCLE[0]! }]);

  useEffect(() => {
    let idx = 0;
    let key = 0;
    let fadeTimer: ReturnType<typeof setTimeout> | undefined;
    const interval = setInterval(() => {
      idx = (idx + 1) % BLEND_CYCLE.length;
      key++;
      const next = { key, mode: BLEND_CYCLE[idx]! };
      setLayers((prev) => [prev[prev.length - 1]!, next]);
      fadeTimer = setTimeout(() => setLayers([next]), BLEND_FADE_MS);
    }, BLEND_INTERVAL_MS);
    return () => {
      clearInterval(interval);
      clearTimeout(fadeTimer);
    };
  }, []);

  return (
    <>
      {layers.map((layer, i) => (
        <div
          key={layer.key}
          className="absolute inset-0 bg-black"
          style={i > 0 ? { animation: `vis-fade-in ${BLEND_FADE_MS}ms ease-in-out both` } : undefined}
        >
          <VisualizerCanvas mode={layer.mode} micAnalyser={micAnalyser} />
        </div>
      ))}
    </>
  );
}

const WEB_GL_MODES: Set<VisMode> = new Set(["tunnel", "kaleidoscope", "warpgrid", "honeycomb", "diamond", "starburst", "spiral", "liquid", "fractal"]);

// ── Canvas renderer ─────────────────────────────────────────────────────

function VisualizerCanvas({ mode, micAnalyser }: { mode: VisMode; micAnalyser: AnalyserNode | null }) {
  const containerRef = useRef<HTMLDivElement>(null);
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const glCanvasRef = useRef<HTMLCanvasElement>(null);
  const rafRef = useRef<number>(0);
  const rendererRef = useRef<{ destroy?: () => void }>({});
  const glCreatedRef = useRef(false);

  // Keep mode in a ref so the animation loop always sees the latest
  const modeRef = useRef(mode);
  modeRef.current = mode;

  const isWebGL = WEB_GL_MODES.has(mode);

  // Resize handler
  const resize = useCallback(() => {
    const container = containerRef.current;
    if (!container) return;
    const { width, height } = container.getBoundingClientRect();
    const dpr = window.devicePixelRatio || 1;

    const canvas2d = canvasRef.current;
    if (canvas2d) {
      canvas2d.width = width * dpr;
      canvas2d.height = height * dpr;
      canvas2d.style.width = `${width}px`;
      canvas2d.style.height = `${height}px`;
    }

    const glCanvas = glCanvasRef.current;
    if (glCanvas) {
      glCanvas.width = width * dpr;
      glCanvas.height = height * dpr;
      glCanvas.style.width = `${width}px`;
      glCanvas.style.height = `${height}px`;
    }
  }, []);

  useEffect(() => {
    resize();
    const ro = new ResizeObserver(resize);
    if (containerRef.current) ro.observe(containerRef.current);
    return () => ro.disconnect();
  }, [resize]);

  // Main render loop
  useEffect(() => {
    // Use mic analyser if available, otherwise fall back to music analyser
    const analyser = micAnalyser || getAnalyserNode();
    if (!analyser) return;

    const freqData = new Uint8Array(analyser.frequencyBinCount);
    const waveData = new Uint8Array(analyser.fftSize);

    // Tear down previous WebGL renderer if any
    rendererRef.current.destroy?.();
    rendererRef.current = {};

    let glState: WebGLState | null = null;
    if (isWebGL && glCanvasRef.current) {
      glState = initWebGL(glCanvasRef.current, mode);
      if (glState) {
        glCreatedRef.current = true;
        rendererRef.current.destroy = () => disposeWebGL(glState!);
      }
    }

    let time = 0;
    let lastFrame = performance.now();

    function loop() {
      const now = performance.now();
      const dt = (now - lastFrame) / 1000;
      lastFrame = now;
      time += dt;

      analyser!.getByteFrequencyData(freqData);
      analyser!.getByteTimeDomainData(waveData);

      const currentMode = modeRef.current;
      const currentIsWebGL = WEB_GL_MODES.has(currentMode);

      if (!currentIsWebGL && canvasRef.current) {
        const ctx = canvasRef.current.getContext("2d");
        if (ctx) {
          const w = canvasRef.current.width;
          const h = canvasRef.current.height;
          switch (currentMode) {
            case "eq":
              drawEQ(ctx, freqData, w, h, time);
              break;
            case "oscilloscope":
              drawOscilloscope(ctx, waveData, w, h, time);
              break;
            case "radial":
              drawRadial(ctx, freqData, waveData, w, h, time);
              break;
          }
        }
      } else if (currentIsWebGL && glState && glCanvasRef.current) {
        renderWebGL(glState, freqData, waveData, time, currentMode);
      }

      rafRef.current = requestAnimationFrame(loop);
    }

    rafRef.current = requestAnimationFrame(loop);

    return () => {
      cancelAnimationFrame(rafRef.current);
      rendererRef.current.destroy?.();
      rendererRef.current = {};
    };
  }, [isWebGL, mode, micAnalyser]);

  // Release the WebGL context on unmount (blend mode creates a fresh canvas each cycle)
  useEffect(() => {
    const glCanvas = glCanvasRef.current;
    return () => {
      if (glCreatedRef.current) {
        glCanvas?.getContext("webgl2")?.getExtension("WEBGL_lose_context")?.loseContext();
      }
    };
  }, []);

  return (
    <div ref={containerRef} className="absolute inset-0">
      <canvas
        ref={canvasRef}
        className="absolute inset-0"
        style={{ display: isWebGL ? "none" : "block" }}
      />
      <canvas
        ref={glCanvasRef}
        className="absolute inset-0"
        style={{ display: isWebGL ? "block" : "none" }}
      />
    </div>
  );
}

// ── Logarithmic frequency mapping ────────────────────────────────────────

/** Map `count` bars onto frequency bins using true logarithmic spacing (constant-Q). */
function logFreqBars(freqData: Uint8Array, count: number): Float32Array {
  const out = new Float32Array(count);
  const numBins = freqData.length;
  const logMin = Math.log(30);
  const logMax = Math.log(numBins);
  const logStep = (logMax - logMin) / count;
  for (let i = 0; i < count; i++) {
    const startBin = Math.max(0, Math.floor(Math.exp(logMin + logStep * i)));
    const endBin = Math.min(numBins, Math.max(startBin + 1, Math.floor(Math.exp(logMin + logStep * (i + 1)))));
    let sum = 0;
    for (let b = startBin; b < endBin; b++) sum += freqData[b]!;
    out[i] = sum / (endBin - startBin) / 255;
  }
  return out;
}

/** Remap raw FFT frequency data to a log-scaled Uint8Array for WebGL texture upload. */
function logFreqTexture(freqData: Uint8Array, size: number): Uint8Array {
  const out = new Uint8Array(size);
  const numBins = freqData.length;
  const logMin = Math.log(30);
  const logMax = Math.log(numBins);
  const logStep = (logMax - logMin) / size;
  for (let i = 0; i < size; i++) {
    const startBin = Math.max(0, Math.floor(Math.exp(logMin + logStep * i)));
    const endBin = Math.min(numBins, Math.max(startBin + 1, Math.floor(Math.exp(logMin + logStep * (i + 1)))));
    let sum = 0;
    for (let b = startBin; b < endBin; b++) sum += freqData[b]!;
    out[i] = Math.round(sum / (endBin - startBin));
  }
  return out;
}

// ── Mode 1: EQ Bars ─────────────────────────────────────────────────────

const eqPeaks: number[] = [];

function drawEQ(ctx: CanvasRenderingContext2D, freqData: Uint8Array, w: number, h: number, _time: number) {
  ctx.clearRect(0, 0, w, h);

  const barCount = 256;
  const gap = 2 * (window.devicePixelRatio || 1);
  const barWidth = (w - gap * (barCount - 1)) / barCount;
  const bars = logFreqBars(freqData, barCount);
  const baseY = h * 0.85;

  for (let i = 0; i < barCount; i++) {
    const val = bars[i]!;
    const barH = val * baseY;

    // Peak tracking
    if (!eqPeaks[i] || eqPeaks[i]! < barH) {
      eqPeaks[i] = barH;
    } else {
      eqPeaks[i] = Math.max(0, eqPeaks[i]! - 1.5 * (window.devicePixelRatio || 1));
    }

    const x = i * (barWidth + gap);

    // Color gradient: warm bass → cool treble
    const hue = 200 - (i / barCount) * 200; // 200 (blue) → 0 (red)
    const sat = 80 + val * 20;
    const light = 45 + val * 20;

    // Main bar
    const grad = ctx.createLinearGradient(x, baseY, x, baseY - barH);
    grad.addColorStop(0, `hsla(${hue}, ${sat}%, ${light * 0.6}%, 1)`);
    grad.addColorStop(1, `hsla(${hue}, ${sat}%, ${light}%, 1)`);
    ctx.fillStyle = grad;
    ctx.fillRect(x, baseY - barH, barWidth, barH);

    // Peak indicator
    if (eqPeaks[i]! > 2) {
      ctx.fillStyle = `hsla(${hue}, 90%, 70%, 0.9)`;
      ctx.fillRect(x, baseY - eqPeaks[i]!, barWidth, 2 * (window.devicePixelRatio || 1));
    }

    // Reflection
    const reflSpace = h - baseY;
    const reflH = Math.min(barH * 0.4, reflSpace);
    const reflGrad = ctx.createLinearGradient(x, baseY, x, baseY + reflH);
    reflGrad.addColorStop(0, `hsla(${hue}, ${sat}%, ${light}%, 0.3)`);
    reflGrad.addColorStop(1, `hsla(${hue}, ${sat}%, ${light}%, 0)`);
    ctx.fillStyle = reflGrad;
    ctx.fillRect(x, baseY, barWidth, reflH);
  }
}

// ── Mode 2: Oscilloscope ─────────────────────────────────────────────────

function drawOscilloscope(ctx: CanvasRenderingContext2D, waveData: Uint8Array, w: number, h: number, time: number) {
  // Trail effect: semi-transparent black fill
  ctx.fillStyle = "rgba(0, 0, 0, 0.15)";
  ctx.fillRect(0, 0, w, h);

  const dpr = window.devicePixelRatio || 1;
  const sliceWidth = w / waveData.length;
  const centerY = h / 2;

  // Slowly cycling hue
  const hue = (time * 30) % 360;

  // Glow effect
  ctx.shadowBlur = 15 * dpr;
  ctx.shadowColor = `hsla(${hue}, 100%, 60%, 0.8)`;
  ctx.strokeStyle = `hsla(${hue}, 100%, 70%, 0.9)`;
  ctx.lineWidth = 2.5 * dpr;
  ctx.lineJoin = "round";
  ctx.lineCap = "round";

  ctx.beginPath();
  for (let i = 0; i < waveData.length; i++) {
    const v = (waveData[i]! - 128) / 128;
    const y = centerY + v * centerY * 0.8;
    const x = i * sliceWidth;
    if (i === 0) {
      ctx.moveTo(x, y);
    } else {
      ctx.lineTo(x, y);
    }
  }
  ctx.stroke();

  // Second dimmer line with offset hue
  ctx.shadowBlur = 8 * dpr;
  ctx.shadowColor = `hsla(${(hue + 120) % 360}, 100%, 60%, 0.4)`;
  ctx.strokeStyle = `hsla(${(hue + 120) % 360}, 100%, 70%, 0.3)`;
  ctx.lineWidth = 1.5 * dpr;

  ctx.beginPath();
  for (let i = 0; i < waveData.length; i++) {
    const v = (waveData[i]! - 128) / 128;
    const y = centerY + v * centerY * 0.6;
    const x = i * sliceWidth;
    if (i === 0) {
      ctx.moveTo(x, y);
    } else {
      ctx.lineTo(x, y);
    }
  }
  ctx.stroke();

  // Reset shadow
  ctx.shadowBlur = 0;
}

// ── Mode 3: Radial Spectrum ──────────────────────────────────────────────

function drawRadial(ctx: CanvasRenderingContext2D, freqData: Uint8Array, waveData: Uint8Array, w: number, h: number, time: number) {
  ctx.fillStyle = "rgba(0, 0, 0, 0.2)";
  ctx.fillRect(0, 0, w, h);

  const dpr = window.devicePixelRatio || 1;
  const cx = w / 2;
  const cy = h / 2;
  const barCount = 512;
  const innerRadius = Math.min(w, h) * 0.12;
  const maxBarLen = Math.min(w, h) * 0.3;
  const bars = logFreqBars(freqData, barCount);
  const rotation = time * 0.2;

  // Compute bass energy for inner circle pulse
  let bassSum = 0;
  for (let i = 0; i < 8; i++) bassSum += freqData[i]!;
  const bassEnergy = bassSum / (8 * 255);
  const pulseRadius = innerRadius * (0.8 + bassEnergy * 0.5);

  // Inner pulsing circle
  const bassHue = (time * 40) % 360;
  ctx.beginPath();
  ctx.arc(cx, cy, pulseRadius, 0, Math.PI * 2);
  ctx.fillStyle = `hsla(${bassHue}, 70%, 20%, 0.6)`;
  ctx.fill();
  ctx.strokeStyle = `hsla(${bassHue}, 80%, 50%, 0.5)`;
  ctx.lineWidth = 1.5 * dpr;
  ctx.stroke();

  // Radial bars
  for (let i = 0; i < barCount; i++) {
    const val = bars[i]!;
    const barLen = val * maxBarLen;

    const angle = (i / barCount) * Math.PI * 2 + rotation;
    const x1 = cx + Math.cos(angle) * innerRadius;
    const y1 = cy + Math.sin(angle) * innerRadius;
    const x2 = cx + Math.cos(angle) * (innerRadius + barLen);
    const y2 = cy + Math.sin(angle) * (innerRadius + barLen);

    const hue = ((i / barCount) * 360 + time * 50) % 360;
    ctx.strokeStyle = `hsla(${hue}, 85%, ${50 + val * 30}%, ${0.5 + val * 0.5})`;
    ctx.lineWidth = (Math.PI * 2 * innerRadius) / barCount * 0.6;
    ctx.lineCap = "round";
    ctx.beginPath();
    ctx.moveTo(x1, y1);
    ctx.lineTo(x2, y2);
    ctx.stroke();
  }

  // Waveform ring
  ctx.beginPath();
  const waveStep = Math.floor(waveData.length / barCount);
  for (let i = 0; i < barCount; i++) {
    const v = (waveData[i * waveStep]! - 128) / 128;
    const r = innerRadius * 0.7 + v * innerRadius * 0.3;
    const angle = (i / barCount) * Math.PI * 2 + rotation;
    const x = cx + Math.cos(angle) * r;
    const y = cy + Math.sin(angle) * r;
    if (i === 0) ctx.moveTo(x, y);
    else ctx.lineTo(x, y);
  }
  ctx.closePath();
  ctx.strokeStyle = `hsla(${(bassHue + 180) % 360}, 60%, 60%, 0.4)`;
  ctx.lineWidth = 1 * dpr;
  ctx.stroke();
}

// ── Modes 4 & 5: WebGL (Tunnel + Kaleidoscope) ──────────────────────────

interface WebGLState {
  gl: WebGL2RenderingContext;
  program: WebGLProgram;
  framebuffers: [WebGLFramebuffer, WebGLFramebuffer];
  textures: [WebGLTexture, WebGLTexture];
  freqTexture: WebGLTexture;
  vao: WebGLVertexArrayObject;
  pingPong: number;
  fboWidth: number;
  fboHeight: number;
}

const FULLSCREEN_QUAD_VS = `#version 300 es
in vec2 a_position;
out vec2 v_uv;
void main() {
  v_uv = a_position * 0.5 + 0.5;
  gl_Position = vec4(a_position, 0.0, 1.0);
}`;

// Single-pass shader: warp previous frame feedback + inject new audio-reactive content
const TUNNEL_FS = `#version 300 es
precision highp float;
uniform sampler2D u_prev;
uniform sampler2D u_freq;
uniform float u_time;
in vec2 v_uv;
out vec4 fragColor;

vec3 hsv2rgb(vec3 c) {
  vec4 K = vec4(1.0, 2.0/3.0, 1.0/3.0, 3.0);
  vec3 p = abs(fract(c.xxx + K.xyz) * 6.0 - K.www);
  return c.z * mix(K.xxx, clamp(p - K.xxx, 0.0, 1.0), c.y);
}

void main() {
  vec2 center = vec2(0.5);
  vec2 d = v_uv - center;
  float dist = length(d);
  float angle = atan(d.y, d.x);

  // Read audio energy
  float bass = texture(u_freq, vec2(0.05, 0.5)).r;
  float mid = texture(u_freq, vec2(0.3, 0.5)).r;
  float treble = texture(u_freq, vec2(0.7, 0.5)).r;
  float energy = (bass + mid + treble) / 3.0;

  // Warp: zoom toward center + rotate
  float zoom = 0.975 - bass * 0.02;
  float rot = (treble - 0.3) * 0.03;
  float s = sin(rot);
  float c = cos(rot);
  vec2 warped = center + vec2(d.x * c - d.y * s, d.x * s + d.y * c) * zoom;

  // Sample previous frame with warp
  vec4 prev = texture(u_prev, warped);
  prev.rgb *= vec3(0.96, 0.965, 0.97); // decay with slight color shift

  // Inject new content: concentric rings modulated by frequency
  float freqAngle = (angle / 3.14159 + 1.0) * 0.5; // 0..1
  float freqSample = texture(u_freq, vec2(freqAngle, 0.5)).r;

  float ring = sin(dist * 25.0 - u_time * 4.0 + bass * 8.0) * 0.5 + 0.5;
  ring *= freqSample * energy;

  // Edge injection: more energy at the edges creates the tunnel inflow
  float edgeFactor = smoothstep(0.3, 0.5, dist);
  ring *= edgeFactor * 1.5;

  // Color
  float hue = mod(u_time * 0.2 + dist * 1.5 + freqAngle * 0.5, 1.0);
  vec3 newColor = hsv2rgb(vec3(hue, 0.85, ring));

  // Also inject bright spots at high energy
  float spark = smoothstep(0.7, 1.0, freqSample) * smoothstep(0.35, 0.5, dist);
  vec3 sparkColor = hsv2rgb(vec3(mod(u_time * 0.5 + angle, 1.0), 0.6, spark * 0.8));

  fragColor = vec4(prev.rgb + newColor * 0.5 + sparkColor * 0.3, 1.0);
}`;

const KALEIDOSCOPE_FS = `#version 300 es
precision highp float;
uniform sampler2D u_prev;
uniform sampler2D u_freq;
uniform float u_time;
in vec2 v_uv;
out vec4 fragColor;

vec3 hsv2rgb(vec3 c) {
  vec4 K = vec4(1.0, 2.0/3.0, 1.0/3.0, 3.0);
  vec3 p = abs(fract(c.xxx + K.xyz) * 6.0 - K.www);
  return c.z * mix(K.xxx, clamp(p - K.xxx, 0.0, 1.0), c.y);
}

void main() {
  vec2 center = vec2(0.5);
  vec2 d = v_uv - center;
  float dist = length(d);
  float angle = atan(d.y, d.x);

  // Audio
  float bass = texture(u_freq, vec2(0.05, 0.5)).r;
  float mid = texture(u_freq, vec2(0.3, 0.5)).r;
  float treble = texture(u_freq, vec2(0.7, 0.5)).r;
  float energy = (bass + mid + treble) / 3.0;

  // 6-fold kaleidoscope symmetry for sampling previous frame
  float folds = 6.0;
  float foldedAngle = abs(mod(angle, 3.14159265 * 2.0 / folds) - 3.14159265 / folds);

  // Reconstruct UV from folded angle
  vec2 foldedD = vec2(cos(foldedAngle), sin(foldedAngle)) * dist;

  // Warp: slight zoom + rotation
  float zoom = 0.985 - bass * 0.012;
  float rot = treble * 0.02 + 0.003;
  float s = sin(rot);
  float c = cos(rot);
  vec2 warped = vec2(foldedD.x * c - foldedD.y * s, foldedD.x * s + foldedD.y * c) * zoom;
  warped += center;

  vec4 prev = texture(u_prev, warped);
  prev.rgb *= 0.965; // decay

  // Inject: plasma patterns
  float p1 = sin(d.x * 12.0 + u_time * 1.3) * sin(d.y * 12.0 + u_time * 0.9);
  float p2 = sin(dist * 15.0 - u_time * 2.0 + bass * 6.0);
  float p3 = sin(angle * 3.0 + u_time * 0.7 + mid * 4.0);
  float plasma = (p1 + p2 + p3) / 3.0 * 0.5 + 0.5;

  // Only inject when there's audio energy
  plasma *= energy * 1.2;

  // Edge injection for mandala patterns
  float edgeRing = smoothstep(0.2, 0.35, dist) * smoothstep(0.5, 0.35, dist);
  plasma += edgeRing * energy * 0.8;

  float hue = mod(u_time * 0.1 + plasma * 0.6 + dist, 1.0);
  float sat = 0.7 + energy * 0.3;
  vec3 newColor = hsv2rgb(vec3(hue, sat, plasma * 0.6));

  // Center glow
  float centerGlow = smoothstep(0.15, 0.0, dist) * bass * 0.5;
  vec3 glowColor = hsv2rgb(vec3(mod(u_time * 0.3, 1.0), 0.5, centerGlow));

  fragColor = vec4(prev.rgb + newColor * 0.35 + glowColor, 1.0);
}`;

const WARPGRID_FS = `#version 300 es
precision highp float;
uniform sampler2D u_prev;
uniform sampler2D u_freq;
uniform float u_time;
in vec2 v_uv;
out vec4 fragColor;

vec3 hsv2rgb(vec3 c) {
  vec4 K = vec4(1.0, 2.0/3.0, 1.0/3.0, 3.0);
  vec3 p = abs(fract(c.xxx + K.xyz) * 6.0 - K.www);
  return c.z * mix(K.xxx, clamp(p - K.xxx, 0.0, 1.0), c.y);
}

void main() {
  vec2 center = vec2(0.5);
  vec2 d = v_uv - center;

  float bass = texture(u_freq, vec2(0.05, 0.5)).r;
  float mid = texture(u_freq, vec2(0.3, 0.5)).r;
  float treble = texture(u_freq, vec2(0.7, 0.5)).r;
  float energy = (bass + mid + treble) / 3.0;

  // Feedback with slight zoom
  float zoom = 0.99 - bass * 0.008;
  vec2 warped = center + d * zoom;
  vec4 prev = texture(u_prev, warped);
  prev.rgb *= 0.94;

  // Grid with audio-driven wave displacement
  vec2 uv = v_uv * 20.0;
  float wave = sin(uv.x + u_time * 2.0 + bass * 6.0) * 0.3 +
               sin(uv.y + u_time * 1.5 + mid * 5.0) * 0.3;
  uv += wave;

  // Grid lines using fract
  vec2 grid = abs(fract(uv) - 0.5);
  float lineX = smoothstep(0.02, 0.0, grid.x);
  float lineY = smoothstep(0.02, 0.0, grid.y);
  float line = max(lineX, lineY);

  // Intersections glow brighter
  float intersection = lineX * lineY * 3.0;

  // Color based on position and audio
  float freqIdx = fract(uv.x * 0.05 + uv.y * 0.03);
  float freq = texture(u_freq, vec2(freqIdx, 0.5)).r;
  float hue = mod(u_time * 0.15 + freq * 0.5 + wave * 0.2, 1.0);
  float brightness = line * (0.3 + freq * 0.7) + intersection * energy;

  vec3 newColor = hsv2rgb(vec3(hue, 0.8, brightness * 0.6));
  fragColor = vec4(prev.rgb + newColor, 1.0);
}`;

const HONEYCOMB_FS = `#version 300 es
precision highp float;
uniform sampler2D u_prev;
uniform sampler2D u_freq;
uniform float u_time;
in vec2 v_uv;
out vec4 fragColor;

vec3 hsv2rgb(vec3 c) {
  vec4 K = vec4(1.0, 2.0/3.0, 1.0/3.0, 3.0);
  vec3 p = abs(fract(c.xxx + K.xyz) * 6.0 - K.www);
  return c.z * mix(K.xxx, clamp(p - K.xxx, 0.0, 1.0), c.y);
}

// Hexagonal distance function
vec4 hexCoord(vec2 uv) {
  vec2 r = vec2(1.0, 1.732);
  vec2 h = r * 0.5;
  vec2 a = mod(uv, r) - h;
  vec2 b = mod(uv - h, r) - h;
  vec2 gv = dot(a, a) < dot(b, b) ? a : b;
  float x = atan(gv.x, gv.y);
  float y = 0.5 - max(abs(gv.x), dot(abs(gv), normalize(h)));
  vec2 id = uv - gv;
  return vec4(x, y, id.x, id.y);
}

void main() {
  vec2 center = vec2(0.5);
  vec2 d = v_uv - center;

  float bass = texture(u_freq, vec2(0.05, 0.5)).r;
  float mid = texture(u_freq, vec2(0.3, 0.5)).r;
  float treble = texture(u_freq, vec2(0.7, 0.5)).r;
  float energy = (bass + mid + treble) / 3.0;

  // Feedback with slight rotation and zoom
  float rot = (treble - 0.3) * 0.008;
  float zoom = 0.992 - bass * 0.006;
  float s = sin(rot);
  float c = cos(rot);
  vec2 warped = center + vec2(d.x * c - d.y * s, d.x * s + d.y * c) * zoom;
  vec4 prev = texture(u_prev, warped);
  prev.rgb *= 0.94;

  // Audio-displaced hex grid
  float scale = 10.0;
  vec2 uv = v_uv * scale;
  uv.x += sin(uv.y * 0.5 + u_time * 1.5 + bass * 4.0) * 0.2;
  uv.y += cos(uv.x * 0.5 + u_time * 1.2 + mid * 3.0) * 0.2;

  vec4 hex = hexCoord(uv);
  float edgeDist = hex.y;

  // Hex cell edges
  float edge = smoothstep(0.05, 0.0, edgeDist);

  // Per-cell audio lookup using cell ID
  float cellFreq = fract(hex.z * 0.127 + hex.w * 0.269);
  float freq = texture(u_freq, vec2(cellFreq, 0.5)).r;

  // Cell fill: pulse from center based on frequency
  float cellFill = smoothstep(0.15, 0.05, edgeDist - freq * 0.12);
  cellFill *= freq * energy;

  // Color
  float hue = mod(u_time * 0.1 + cellFreq * 0.6 + freq * 0.3, 1.0);
  float sat = 0.75 + energy * 0.2;
  vec3 edgeColor = hsv2rgb(vec3(hue, sat, edge * (0.4 + freq * 0.6)));
  vec3 fillColor = hsv2rgb(vec3(mod(hue + 0.15, 1.0), sat * 0.8, cellFill * 0.5));

  // Vertex glow at hex corners
  float corner = smoothstep(0.02, 0.0, edgeDist) * smoothstep(0.5, 0.0, abs(mod(hex.x * 0.955, 1.047) - 0.524));
  vec3 cornerColor = hsv2rgb(vec3(mod(hue + 0.3, 1.0), 0.6, corner * energy * 1.5));

  fragColor = vec4(prev.rgb + edgeColor * 0.4 + fillColor * 0.3 + cornerColor * 0.2, 1.0);
}`;

const DIAMOND_FS = `#version 300 es
precision highp float;
uniform sampler2D u_prev;
uniform sampler2D u_freq;
uniform float u_time;
in vec2 v_uv;
out vec4 fragColor;

vec3 hsv2rgb(vec3 c) {
  vec4 K = vec4(1.0, 2.0/3.0, 1.0/3.0, 3.0);
  vec3 p = abs(fract(c.xxx + K.xyz) * 6.0 - K.www);
  return c.z * mix(K.xxx, clamp(p - K.xxx, 0.0, 1.0), c.y);
}

void main() {
  vec2 center = vec2(0.5);
  vec2 d = v_uv - center;
  float dist = length(d);

  float bass = texture(u_freq, vec2(0.05, 0.5)).r;
  float mid = texture(u_freq, vec2(0.3, 0.5)).r;
  float treble = texture(u_freq, vec2(0.7, 0.5)).r;
  float energy = (bass + mid + treble) / 3.0;

  // Feedback with slow rotation
  float rot = 0.008 + mid * 0.006;
  float zoom = 0.993 - bass * 0.005;
  float s = sin(rot);
  float c = cos(rot);
  vec2 warped = center + vec2(d.x * c - d.y * s, d.x * s + d.y * c) * zoom;
  vec4 prev = texture(u_prev, warped);
  prev.rgb *= 0.945;

  // Rotate UV 45 degrees to create diamond grid from square grid
  float angle = 0.7854 + sin(u_time * 0.4) * 0.1;
  float sa = sin(angle);
  float ca = cos(angle);
  vec2 ruv = vec2(d.x * ca - d.y * sa, d.x * sa + d.y * ca);

  // Multi-scale diamond lattice
  float scale1 = 12.0 + bass * 4.0;
  float scale2 = scale1 * 2.0;

  // Wave displacement
  vec2 uv1 = ruv * scale1;
  uv1 += vec2(sin(uv1.y * 0.3 + u_time * 1.8) * 0.25 * bass,
              cos(uv1.x * 0.3 + u_time * 1.3) * 0.25 * mid);

  vec2 uv2 = ruv * scale2;
  uv2 += vec2(sin(uv2.y * 0.2 + u_time * 2.2) * 0.15 * treble,
              cos(uv2.x * 0.2 + u_time * 1.7) * 0.15 * energy);

  // Diamond edges (Manhattan distance in rotated space)
  vec2 cell1 = abs(fract(uv1) - 0.5);
  float diamond1 = cell1.x + cell1.y;
  float edge1 = smoothstep(0.02, 0.0, abs(diamond1 - 0.5));

  vec2 cell2 = abs(fract(uv2) - 0.5);
  float diamond2 = cell2.x + cell2.y;
  float edge2 = smoothstep(0.015, 0.0, abs(diamond2 - 0.5));

  // Cell centers — bright pulsing nodes
  float center1 = smoothstep(0.25, 0.0, diamond1);
  float center2 = smoothstep(0.2, 0.0, diamond2);

  // Audio-driven coloring per cell
  vec2 cellId1 = floor(uv1);
  float cellFreq1 = fract(cellId1.x * 0.173 + cellId1.y * 0.317);
  float freq1 = texture(u_freq, vec2(cellFreq1, 0.5)).r;

  vec2 cellId2 = floor(uv2);
  float cellFreq2 = fract(cellId2.x * 0.237 + cellId2.y * 0.419);
  float freq2 = texture(u_freq, vec2(cellFreq2, 0.5)).r;

  // Colors
  float hue1 = mod(u_time * 0.12 + cellFreq1 * 0.5 + dist * 0.8, 1.0);
  float hue2 = mod(u_time * 0.12 + cellFreq2 * 0.5 + dist * 1.2 + 0.33, 1.0);

  // Large lattice: thicker, brighter edges
  vec3 color1 = hsv2rgb(vec3(hue1, 0.8, edge1 * (0.5 + freq1 * 0.5)));
  vec3 node1 = hsv2rgb(vec3(mod(hue1 + 0.15, 1.0), 0.6, center1 * freq1 * energy * 0.8));

  // Small lattice: thinner, subtler overlay
  vec3 color2 = hsv2rgb(vec3(hue2, 0.85, edge2 * (0.3 + freq2 * 0.4)));
  vec3 node2 = hsv2rgb(vec3(mod(hue2 + 0.2, 1.0), 0.7, center2 * freq2 * energy * 0.5));

  // Radial vignette — more energy toward edges
  float vignette = smoothstep(0.0, 0.4, dist) * 0.3;

  fragColor = vec4(prev.rgb + (color1 + node1) * 0.4 + (color2 + node2) * 0.25 + vignette * energy * 0.1, 1.0);
}`;

const STARBURST_FS = `#version 300 es
precision highp float;
uniform sampler2D u_prev;
uniform sampler2D u_freq;
uniform float u_time;
in vec2 v_uv;
out vec4 fragColor;

vec3 hsv2rgb(vec3 c) {
  vec4 K = vec4(1.0, 2.0/3.0, 1.0/3.0, 3.0);
  vec3 p = abs(fract(c.xxx + K.xyz) * 6.0 - K.www);
  return c.z * mix(K.xxx, clamp(p - K.xxx, 0.0, 1.0), c.y);
}

void main() {
  vec2 center = vec2(0.5);
  vec2 d = v_uv - center;
  float dist = length(d);
  float angle = atan(d.y, d.x);

  float bass = texture(u_freq, vec2(0.05, 0.5)).r;
  float mid = texture(u_freq, vec2(0.3, 0.5)).r;
  float treble = texture(u_freq, vec2(0.7, 0.5)).r;
  float energy = (bass + mid + treble) / 3.0;

  // Feedback: zoom outward from center
  float zoom = 1.02 + bass * 0.015;
  vec2 warped = center + d * zoom;
  vec4 prev = texture(u_prev, warped);
  prev.rgb *= 0.935;

  // Rays from center
  float rayCount = 12.0 + treble * 20.0;
  float rayAngle = mod(angle, 6.28318 / rayCount);
  float rayDist = abs(rayAngle - 3.14159 / rayCount);
  float ray = smoothstep(0.08, 0.0, rayDist) / (dist * 4.0 + 0.5);
  ray *= energy * 1.5;

  // Burst flash at center on bass hits
  float flash = smoothstep(0.6, 1.0, bass) * smoothstep(0.15, 0.0, dist) * 2.0;

  // Frequency-modulated rings expanding outward
  float ring = sin(dist * 30.0 - u_time * 8.0 * (0.5 + bass)) * 0.5 + 0.5;
  ring *= smoothstep(0.0, 0.1, dist) * smoothstep(0.5, 0.3, dist) * mid;

  float hue = mod(angle / 6.28318 + u_time * 0.2, 1.0);
  vec3 rayColor = hsv2rgb(vec3(hue, 0.9, ray));
  vec3 flashColor = hsv2rgb(vec3(mod(u_time * 0.4, 1.0), 0.4, flash));
  vec3 ringColor = hsv2rgb(vec3(mod(hue + 0.3, 1.0), 0.7, ring * 0.5));

  fragColor = vec4(prev.rgb + rayColor * 0.5 + flashColor + ringColor * 0.3, 1.0);
}`;

const SPIRAL_FS = `#version 300 es
precision highp float;
uniform sampler2D u_prev;
uniform sampler2D u_freq;
uniform float u_time;
in vec2 v_uv;
out vec4 fragColor;

vec3 hsv2rgb(vec3 c) {
  vec4 K = vec4(1.0, 2.0/3.0, 1.0/3.0, 3.0);
  vec3 p = abs(fract(c.xxx + K.xyz) * 6.0 - K.www);
  return c.z * mix(K.xxx, clamp(p - K.xxx, 0.0, 1.0), c.y);
}

void main() {
  vec2 center = vec2(0.5);
  vec2 d = v_uv - center;
  float dist = length(d);
  float angle = atan(d.y, d.x);

  float bass = texture(u_freq, vec2(0.05, 0.5)).r;
  float mid = texture(u_freq, vec2(0.3, 0.5)).r;
  float treble = texture(u_freq, vec2(0.7, 0.5)).r;
  float energy = (bass + mid + treble) / 3.0;

  // Feedback with rotation - creates trailing spiral arms
  float rot = 0.015 + mid * 0.01;
  float zoom = 0.995 - bass * 0.005;
  float s = sin(rot);
  float c = cos(rot);
  vec2 warped = center + vec2(d.x * c - d.y * s, d.x * s + d.y * c) * zoom;
  vec4 prev = texture(u_prev, warped);
  prev.rgb *= 0.96;

  // Logarithmic spiral arms
  float armCount = 3.0 + bass * 3.0;
  float twist = 2.5 + mid * 2.0;
  float spiral = sin(angle * armCount - log(dist + 0.001) * twist * 10.0 + u_time * 2.0);
  spiral = smoothstep(0.3, 1.0, spiral);

  // Stars along arms using pseudo-random noise
  float stars = fract(sin(dot(floor(v_uv * 80.0), vec2(12.9898, 78.233))) * 43758.5453);
  stars = smoothstep(0.97, 1.0, stars) * spiral * energy * 2.0;

  // Nebula glow along arms
  float nebula = spiral * smoothstep(0.0, 0.3, dist) * smoothstep(0.5, 0.2, dist);
  nebula *= energy;

  // Sample frequency along the spiral
  float freqIdx = mod(angle / 6.28318 + 0.5, 1.0);
  float freq = texture(u_freq, vec2(freqIdx, 0.5)).r;

  float hue = mod(u_time * 0.08 + dist * 1.5 + angle * 0.1, 1.0);
  vec3 nebulaColor = hsv2rgb(vec3(hue, 0.7, nebula * 0.5 * freq));
  vec3 starColor = vec3(stars * 0.8);

  // Core glow
  float core = smoothstep(0.08, 0.0, dist) * (0.3 + bass * 0.5);
  vec3 coreColor = hsv2rgb(vec3(mod(u_time * 0.1, 1.0), 0.3, core));

  fragColor = vec4(prev.rgb + nebulaColor * 0.4 + starColor + coreColor, 1.0);
}`;

const LIQUID_FS = `#version 300 es
precision highp float;
uniform sampler2D u_prev;
uniform sampler2D u_freq;
uniform float u_time;
in vec2 v_uv;
out vec4 fragColor;

vec3 hsv2rgb(vec3 c) {
  vec4 K = vec4(1.0, 2.0/3.0, 1.0/3.0, 3.0);
  vec3 p = abs(fract(c.xxx + K.xyz) * 6.0 - K.www);
  return c.z * mix(K.xxx, clamp(p - K.xxx, 0.0, 1.0), c.y);
}

void main() {
  vec2 center = vec2(0.5);
  vec2 d = v_uv - center;

  float bass = texture(u_freq, vec2(0.05, 0.5)).r;
  float mid = texture(u_freq, vec2(0.3, 0.5)).r;
  float treble = texture(u_freq, vec2(0.7, 0.5)).r;
  float energy = (bass + mid + treble) / 3.0;

  // Feedback: gentle swirl
  float rot = sin(u_time * 0.3) * 0.005;
  float s = sin(rot);
  float c = cos(rot);
  vec2 warped = center + vec2(d.x * c - d.y * s, d.x * s + d.y * c) * 0.998;
  vec4 prev = texture(u_prev, warped);
  prev.rgb *= 0.95;

  // Metaball centers orbiting
  float speed = 1.0 + energy * 2.0;
  vec2 b1 = center + vec2(sin(u_time * speed * 0.7) * 0.25, cos(u_time * speed * 0.5) * 0.2);
  vec2 b2 = center + vec2(cos(u_time * speed * 0.6) * 0.2, sin(u_time * speed * 0.8) * 0.25);
  vec2 b3 = center + vec2(sin(u_time * speed * 0.9 + 2.0) * 0.18, cos(u_time * speed * 0.4 + 1.0) * 0.22);
  vec2 b4 = center + vec2(cos(u_time * speed * 0.5 + 3.5) * 0.22, sin(u_time * speed * 0.7 + 2.5) * 0.18);

  // Metaball radii driven by audio
  float r1 = 0.06 + bass * 0.04;
  float r2 = 0.05 + mid * 0.04;
  float r3 = 0.05 + treble * 0.03;
  float r4 = 0.04 + energy * 0.03;

  // Metaball field
  float field = r1 / length(v_uv - b1) +
                r2 / length(v_uv - b2) +
                r3 / length(v_uv - b3) +
                r4 / length(v_uv - b4);

  // Smooth blob threshold
  float blob = smoothstep(3.0, 5.0, field);
  float edge = smoothstep(4.5, 5.0, field) - smoothstep(5.0, 5.5, field);

  // Color based on field strength and position
  float hue = mod(u_time * 0.12 + field * 0.1 + v_uv.x * 0.3, 1.0);
  float sat = 0.6 + energy * 0.3;
  vec3 blobColor = hsv2rgb(vec3(hue, sat, blob * 0.5));
  vec3 edgeColor = hsv2rgb(vec3(mod(hue + 0.2, 1.0), 0.9, edge * 0.8));

  fragColor = vec4(prev.rgb + blobColor * 0.4 + edgeColor * 0.3, 1.0);
}`;

const FRACTAL_FS = `#version 300 es
precision highp float;
uniform sampler2D u_prev;
uniform sampler2D u_freq;
uniform float u_time;
in vec2 v_uv;
out vec4 fragColor;

vec3 hsv2rgb(vec3 c) {
  vec4 K = vec4(1.0, 2.0/3.0, 1.0/3.0, 3.0);
  vec3 p = abs(fract(c.xxx + K.xyz) * 6.0 - K.www);
  return c.z * mix(K.xxx, clamp(p - K.xxx, 0.0, 1.0), c.y);
}

void main() {
  vec2 center = vec2(0.5);
  vec2 d = v_uv - center;
  float dist = length(d);

  float bass = texture(u_freq, vec2(0.05, 0.5)).r;
  float mid = texture(u_freq, vec2(0.3, 0.5)).r;
  float treble = texture(u_freq, vec2(0.7, 0.5)).r;
  float energy = (bass + mid + treble) / 3.0;

  // Feedback: zoom into center for infinite tunnel effect
  float zoom = 0.985 - bass * 0.01;
  vec2 warped = center + d * zoom;
  vec4 prev = texture(u_prev, warped);
  prev.rgb *= 0.955;

  // Julia set with audio-reactive c parameter
  vec2 c_param = vec2(
    -0.7 + sin(u_time * 0.3) * 0.15 + bass * 0.1 - 0.05,
    0.27 + cos(u_time * 0.2) * 0.1 + treble * 0.08 - 0.04
  );

  // Map UV to complex plane
  vec2 z = (v_uv - 0.5) * 3.0;
  float iter = 0.0;
  float maxIter = 20.0;

  for (float i = 0.0; i < 20.0; i++) {
    // z = z^2 + c
    z = vec2(z.x * z.x - z.y * z.y, 2.0 * z.x * z.y) + c_param;
    if (dot(z, z) > 4.0) break;
    iter += 1.0;
  }

  // Smooth iteration count
  float smoothIter = iter;
  if (iter < maxIter) {
    float log_zn = log(dot(z, z)) * 0.5;
    float nu = log(log_zn / log(2.0)) / log(2.0);
    smoothIter = iter + 1.0 - nu;
  }

  // Color by iteration count
  float t = smoothIter / maxIter;
  float inSet = step(maxIter - 0.5, iter);

  // Fractal edge coloring
  float hue = mod(t * 2.0 + u_time * 0.15, 1.0);
  float brightness = (1.0 - inSet) * t * (0.5 + energy * 0.5);
  vec3 fractalColor = hsv2rgb(vec3(hue, 0.85 - t * 0.3, brightness));

  // Inner set glow on bass
  vec3 innerGlow = hsv2rgb(vec3(mod(u_time * 0.2, 1.0), 0.5, inSet * bass * 0.3));

  fragColor = vec4(prev.rgb + fractalColor * 0.35 + innerGlow * 0.2, 1.0);
}`;

function createShader(gl: WebGL2RenderingContext, type: number, source: string): WebGLShader | null {
  const shader = gl.createShader(type);
  if (!shader) return null;
  gl.shaderSource(shader, source);
  gl.compileShader(shader);
  if (!gl.getShaderParameter(shader, gl.COMPILE_STATUS)) {
    console.error("Shader compile error:", gl.getShaderInfoLog(shader));
    gl.deleteShader(shader);
    return null;
  }
  return shader;
}

function createProgram(gl: WebGL2RenderingContext, vsSource: string, fsSource: string): WebGLProgram | null {
  const vs = createShader(gl, gl.VERTEX_SHADER, vsSource);
  const fs = createShader(gl, gl.FRAGMENT_SHADER, fsSource);
  if (!vs || !fs) return null;

  const program = gl.createProgram();
  if (!program) return null;
  gl.attachShader(program, vs);
  gl.attachShader(program, fs);
  gl.linkProgram(program);
  if (!gl.getProgramParameter(program, gl.LINK_STATUS)) {
    console.error("Program link error:", gl.getProgramInfoLog(program));
    gl.deleteProgram(program);
    return null;
  }
  gl.deleteShader(vs);
  gl.deleteShader(fs);
  return program;
}

function createFBOTexture(gl: WebGL2RenderingContext, w: number, h: number): { fb: WebGLFramebuffer; tex: WebGLTexture } | null {
  const tex = gl.createTexture();
  if (!tex) return null;
  gl.bindTexture(gl.TEXTURE_2D, tex);
  gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, w, h, 0, gl.RGBA, gl.UNSIGNED_BYTE, null);
  gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR);
  gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
  gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
  gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);

  const fb = gl.createFramebuffer();
  if (!fb) return null;
  gl.bindFramebuffer(gl.FRAMEBUFFER, fb);
  gl.framebufferTexture2D(gl.FRAMEBUFFER, gl.COLOR_ATTACHMENT0, gl.TEXTURE_2D, tex, 0);
  gl.bindFramebuffer(gl.FRAMEBUFFER, null);

  return { fb, tex };
}

function initWebGL(canvas: HTMLCanvasElement, mode: VisMode): WebGLState | null {
  const gl = canvas.getContext("webgl2", { antialias: false });
  if (!gl) {
    console.error("WebGL2 not available");
    return null;
  }

  const shaderMap: Record<string, string> = {
    tunnel: TUNNEL_FS,
    kaleidoscope: KALEIDOSCOPE_FS,
    warpgrid: WARPGRID_FS,
    honeycomb: HONEYCOMB_FS,
    diamond: DIAMOND_FS,
    starburst: STARBURST_FS,
    spiral: SPIRAL_FS,
    liquid: LIQUID_FS,
    fractal: FRACTAL_FS,
  };
  const fs = shaderMap[mode] || TUNNEL_FS;
  const program = createProgram(gl, FULLSCREEN_QUAD_VS, fs);
  if (!program) return null;

  // Fullscreen quad VAO
  const vao = gl.createVertexArray();
  if (!vao) return null;
  gl.bindVertexArray(vao);
  const vb = gl.createBuffer();
  gl.bindBuffer(gl.ARRAY_BUFFER, vb);
  gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1, -1, 1, -1, -1, 1, 1, 1]), gl.STATIC_DRAW);
  const aPos = gl.getAttribLocation(program, "a_position");
  gl.enableVertexAttribArray(aPos);
  gl.vertexAttribPointer(aPos, 2, gl.FLOAT, false, 0, 0);
  gl.bindVertexArray(null);

  // Ping-pong framebuffers
  const w = canvas.width || 800;
  const h = canvas.height || 600;
  const fbo0 = createFBOTexture(gl, w, h);
  const fbo1 = createFBOTexture(gl, w, h);
  if (!fbo0 || !fbo1) return null;

  // Frequency data texture (1D, uploaded each frame)
  const freqTex = gl.createTexture();
  if (!freqTex) return null;
  gl.bindTexture(gl.TEXTURE_2D, freqTex);
  gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR);
  gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
  gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
  gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);

  return {
    gl,
    program,
    framebuffers: [fbo0.fb, fbo1.fb],
    textures: [fbo0.tex, fbo1.tex],
    freqTexture: freqTex,
    vao,
    pingPong: 0,
    fboWidth: w,
    fboHeight: h,
  };
}

function renderWebGL(state: WebGLState, freqData: Uint8Array, _waveData: Uint8Array, time: number, _mode: VisMode) {
  const { gl, program, framebuffers, textures, freqTexture, vao } = state;

  const w = gl.canvas.width;
  const h = gl.canvas.height;

  // Resize FBO textures if canvas size changed
  if (w !== state.fboWidth || h !== state.fboHeight) {
    for (let i = 0; i < 2; i++) {
      gl.bindTexture(gl.TEXTURE_2D, textures[i as 0 | 1]!);
      gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, w, h, 0, gl.RGBA, gl.UNSIGNED_BYTE, null);
    }
    state.fboWidth = w;
    state.fboHeight = h;
  }

  // Upload log-remapped frequency data as a 1D texture
  const logFreq = logFreqTexture(freqData, 256);
  gl.bindTexture(gl.TEXTURE_2D, freqTexture);
  gl.texImage2D(gl.TEXTURE_2D, 0, gl.LUMINANCE, 256, 1, 0, gl.LUMINANCE, gl.UNSIGNED_BYTE, logFreq);

  const readIdx = state.pingPong as 0 | 1;
  const writeIdx = (1 - readIdx) as 0 | 1;

  // Render to write FBO: warp previous + inject new content
  gl.bindFramebuffer(gl.FRAMEBUFFER, framebuffers[writeIdx]!);
  gl.viewport(0, 0, w, h);
  gl.useProgram(program);

  gl.activeTexture(gl.TEXTURE0);
  gl.bindTexture(gl.TEXTURE_2D, textures[readIdx]!);
  gl.uniform1i(gl.getUniformLocation(program, "u_prev"), 0);

  gl.activeTexture(gl.TEXTURE1);
  gl.bindTexture(gl.TEXTURE_2D, freqTexture);
  gl.uniform1i(gl.getUniformLocation(program, "u_freq"), 1);

  gl.uniform1f(gl.getUniformLocation(program, "u_time"), time);

  gl.bindVertexArray(vao);
  gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4);

  // Blit write FBO to screen
  gl.bindFramebuffer(gl.READ_FRAMEBUFFER, framebuffers[writeIdx]!);
  gl.bindFramebuffer(gl.DRAW_FRAMEBUFFER, null);
  gl.blitFramebuffer(0, 0, w, h, 0, 0, w, h, gl.COLOR_BUFFER_BIT, gl.NEAREST);
  gl.bindFramebuffer(gl.READ_FRAMEBUFFER, null);

  state.pingPong = writeIdx;
}

function disposeWebGL(state: WebGLState) {
  const { gl, program, framebuffers, textures, freqTexture, vao } = state;
  gl.deleteProgram(program);
  for (const fb of framebuffers) gl.deleteFramebuffer(fb);
  for (const tex of textures) gl.deleteTexture(tex);
  gl.deleteTexture(freqTexture);
  gl.deleteVertexArray(vao);
}
