import { CloudAmpLogo } from "@/components/CloudAmpLogo";
import { useEffect, useRef, useState } from "react";

// ── Animated EQ bars for hero background ─────────────────────────────────
function AnimatedEQBars() {
  const canvasRef = useRef<HTMLCanvasElement>(null);

  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;
    const ctx = canvas.getContext("2d");
    if (!ctx) return;

    let raf: number;
    const barCount = 64;
    const heights = Array.from({ length: barCount }, () => Math.random());
    const velocities = Array.from({ length: barCount }, () => Math.random() * 0.02 + 0.005);
    const targets = Array.from({ length: barCount }, () => Math.random());

    function resize() {
      const dpr = window.devicePixelRatio || 1;
      const rect = canvas!.getBoundingClientRect();
      canvas!.width = rect.width * dpr;
      canvas!.height = rect.height * dpr;
    }

    resize();
    const ro = new ResizeObserver(resize);
    ro.observe(canvas);

    function draw() {
      const w = canvas!.width;
      const h = canvas!.height;

      // Skip drawing if canvas has no size yet
      if (w < 1 || h < 1) {
        raf = requestAnimationFrame(draw);
        return;
      }

      ctx!.clearRect(0, 0, w, h);

      const gap = 2 * (window.devicePixelRatio || 1);
      const barWidth = (w - gap * (barCount - 1)) / barCount;
      const baseY = h;

      for (let i = 0; i < barCount; i++) {
        const diff = (targets[i] ?? 0) - (heights[i] ?? 0);
        heights[i] = (heights[i] ?? 0) + diff * (velocities[i] ?? 0.01) * 3;

        if (Math.abs(diff) < 0.01) {
          targets[i] = Math.random() * 0.6 + 0.1;
          velocities[i] = Math.random() * 0.02 + 0.008;
        }

        const val = heights[i] ?? 0;
        const barH = val * h * 0.9;
        const x = i * (barWidth + gap);

        // Guard: skip bar if height is effectively zero (avoids degenerate gradient)
        if (barH < 0.5) continue;

        const hue = 200 - (i / barCount) * 200;
        const sat = 70;
        const light = 40 + val * 15;

        const grad = ctx!.createLinearGradient(x, baseY, x, baseY - barH);
        grad.addColorStop(0, `hsla(${hue}, ${sat}%, ${light * 0.5}%, 0.15)`);
        grad.addColorStop(1, `hsla(${hue}, ${sat}%, ${light}%, 0.25)`);
        ctx!.fillStyle = grad;
        ctx!.fillRect(x, baseY - barH, barWidth, barH);
      }

      raf = requestAnimationFrame(draw);
    }

    raf = requestAnimationFrame(draw);
    return () => {
      cancelAnimationFrame(raf);
      ro.disconnect();
    };
  }, []);

  return (
    <canvas
      ref={canvasRef}
      className="absolute inset-0 w-full h-full pointer-events-none"
      aria-hidden="true"
    />
  );
}

// ── Feature card ─────────────────────────────────────────────────────────
function FeatureCard({
  icon,
  title,
  description,
}: {
  icon: React.ReactNode;
  title: string;
  description: string;
}) {
  return (
    <div className="group relative rounded-2xl border border-zinc-800/60 bg-zinc-900/50 p-6 backdrop-blur-sm transition-all duration-300 hover:border-zinc-700/80 hover:bg-zinc-900/80 hover:shadow-lg hover:shadow-blue-500/5">
      <div className="mb-4 flex h-12 w-12 items-center justify-center rounded-xl bg-zinc-800/80 text-blue-400 transition-colors group-hover:bg-blue-500/10 group-hover:text-blue-300">
        {icon}
      </div>
      <h3 className="mb-2 text-lg font-semibold text-white">{title}</h3>
      <p className="text-sm leading-relaxed text-zinc-400">{description}</p>
    </div>
  );
}

// ── Shared hook: draw on canvas once it has a real size ──────────────────

function useCanvasDraw(draw: (ctx: CanvasRenderingContext2D, w: number, h: number, dpr: number) => void) {
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const drawnRef = useRef(false);

  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;

    function tryDraw() {
      if (drawnRef.current) return;
      const rect = canvas!.getBoundingClientRect();
      if (rect.width < 1 || rect.height < 1) return; // not laid out yet
      drawnRef.current = true;
      const dpr = window.devicePixelRatio || 1;
      canvas!.width = rect.width * dpr;
      canvas!.height = rect.height * dpr;
      const ctx = canvas!.getContext("2d");
      if (!ctx) return;
      draw(ctx, canvas!.width, canvas!.height, dpr);
    }

    // Try immediately (may work if already laid out)
    tryDraw();
    if (drawnRef.current) return;

    // Otherwise wait for layout via ResizeObserver
    const ro = new ResizeObserver(() => tryDraw());
    ro.observe(canvas);
    return () => ro.disconnect();
  }, [draw]);

  return canvasRef;
}

// ── Mini canvas visualizer previews ──────────────────────────────────────

function VisPreviewEQ({ className }: { className?: string }) {
  const drawFn = useRef((ctx: CanvasRenderingContext2D, w: number, h: number, dpr: number) => {
    ctx.clearRect(0, 0, w, h);
    const barCount = 48;
    const gap = 1.5 * dpr;
    const barWidth = (w - gap * (barCount - 1)) / barCount;
    const baseY = h * 0.88;
    for (let i = 0; i < barCount; i++) {
      const raw = Math.sin(i * 0.15 + 1) * 0.4 + 0.5 + Math.sin(i * 0.4) * 0.2;
      const val = Math.pow(Math.max(0, raw), 0.8);
      const barH = val * baseY * 0.9;
      const x = i * (barWidth + gap);
      if (barH < 0.5) continue;
      const hue = 200 - (i / barCount) * 200;
      const grad = ctx.createLinearGradient(x, baseY, x, baseY - barH);
      grad.addColorStop(0, `hsla(${hue}, 80%, 30%, 0.9)`);
      grad.addColorStop(1, `hsla(${hue}, 85%, ${50 + val * 20}%, 1)`);
      ctx.fillStyle = grad;
      ctx.fillRect(x, baseY - barH, barWidth, barH);
      ctx.fillStyle = `hsla(${hue}, 90%, 70%, 0.9)`;
      ctx.fillRect(x, baseY - barH - 3 * dpr, barWidth, 2 * dpr);
      const reflH = barH * 0.3;
      const rGrad = ctx.createLinearGradient(x, baseY, x, baseY + reflH);
      rGrad.addColorStop(0, `hsla(${hue}, 80%, 40%, 0.25)`);
      rGrad.addColorStop(1, `hsla(${hue}, 80%, 40%, 0)`);
      ctx.fillStyle = rGrad;
      ctx.fillRect(x, baseY, barWidth, reflH);
    }
  }).current;
  const canvasRef = useCanvasDraw(drawFn);
  return <canvas ref={canvasRef} className={className} />;
}

function VisPreviewOscilloscope({ className }: { className?: string }) {
  const drawFn = useRef((ctx: CanvasRenderingContext2D, w: number, h: number, dpr: number) => {
    ctx.fillStyle = "#000";
    ctx.fillRect(0, 0, w, h);
    const cy = h / 2;
    ctx.shadowBlur = 8 * dpr;
    ctx.shadowColor = "hsla(180, 100%, 60%, 0.4)";
    ctx.strokeStyle = "hsla(180, 100%, 70%, 0.25)";
    ctx.lineWidth = 1.5 * dpr;
    ctx.lineJoin = "round";
    ctx.beginPath();
    for (let x = 0; x < w; x++) {
      const t = x / w;
      const y = cy + (Math.sin(t * 14 + 2) * 0.25 + Math.sin(t * 7) * 0.15) * cy;
      x === 0 ? ctx.moveTo(x, y) : ctx.lineTo(x, y);
    }
    ctx.stroke();
    ctx.shadowBlur = 15 * dpr;
    ctx.shadowColor = "hsla(60, 100%, 60%, 0.8)";
    ctx.strokeStyle = "hsla(60, 100%, 70%, 0.9)";
    ctx.lineWidth = 2.5 * dpr;
    ctx.beginPath();
    for (let x = 0; x < w; x++) {
      const t = x / w;
      const y = cy + (Math.sin(t * 12) * 0.35 + Math.sin(t * 5 + 0.5) * 0.2) * cy;
      x === 0 ? ctx.moveTo(x, y) : ctx.lineTo(x, y);
    }
    ctx.stroke();
    ctx.shadowBlur = 0;
  }).current;
  const canvasRef = useCanvasDraw(drawFn);
  return <canvas ref={canvasRef} className={className} />;
}

function VisPreviewRadial({ className }: { className?: string }) {
  const drawFn = useRef((ctx: CanvasRenderingContext2D, w: number, h: number, dpr: number) => {
    ctx.fillStyle = "#000";
    ctx.fillRect(0, 0, w, h);
    const cx = w / 2, cy = h / 2;
    const innerR = Math.min(w, h) * 0.12;
    const maxBar = Math.min(w, h) * 0.28;
    const barCount = 128;
    ctx.beginPath();
    ctx.arc(cx, cy, innerR * 0.85, 0, Math.PI * 2);
    ctx.fillStyle = "hsla(200, 70%, 20%, 0.5)";
    ctx.fill();
    ctx.strokeStyle = "hsla(200, 80%, 50%, 0.4)";
    ctx.lineWidth = 1.5 * dpr;
    ctx.stroke();
    for (let i = 0; i < barCount; i++) {
      const val = Math.pow(Math.sin(i * 0.08) * 0.4 + 0.5 + Math.sin(i * 0.2) * 0.15, 0.7);
      const barLen = val * maxBar;
      const angle = (i / barCount) * Math.PI * 2;
      const x1 = cx + Math.cos(angle) * innerR;
      const y1 = cy + Math.sin(angle) * innerR;
      const x2 = cx + Math.cos(angle) * (innerR + barLen);
      const y2 = cy + Math.sin(angle) * (innerR + barLen);
      const hue = (i / barCount) * 360;
      ctx.strokeStyle = `hsla(${hue}, 85%, ${50 + val * 25}%, ${0.5 + val * 0.5})`;
      ctx.lineWidth = (Math.PI * 2 * innerR) / barCount * 0.6;
      ctx.lineCap = "round";
      ctx.beginPath();
      ctx.moveTo(x1, y1);
      ctx.lineTo(x2, y2);
      ctx.stroke();
    }
  }).current;
  const canvasRef = useCanvasDraw(drawFn);
  return <canvas ref={canvasRef} className={className} />;
}

function VisPreviewTunnel({ className }: { className?: string }) {
  const drawFn = useRef((ctx: CanvasRenderingContext2D, w: number, h: number, dpr: number) => {
    ctx.fillStyle = "#000";
    ctx.fillRect(0, 0, w, h);
    const cx = w / 2, cy = h / 2;
    for (let r = 18; r > 0; r--) {
      const radius = (r / 18) * Math.min(w, h) * 0.52;
      const hue = (r * 25 + 180) % 360;
      const intensity = 0.15 + (Math.sin(r * 0.6) * 0.5 + 0.5) * 0.4;
      ctx.beginPath();
      ctx.arc(cx, cy, radius, 0, Math.PI * 2);
      ctx.strokeStyle = `hsla(${hue}, 85%, 55%, ${intensity})`;
      ctx.lineWidth = 2 * dpr;
      ctx.stroke();
      const grd = ctx.createRadialGradient(cx, cy, Math.max(0, radius - 4 * dpr), cx, cy, radius + 4 * dpr);
      grd.addColorStop(0, `hsla(${hue}, 80%, 50%, 0)`);
      grd.addColorStop(0.5, `hsla(${hue}, 80%, 50%, ${intensity * 0.3})`);
      grd.addColorStop(1, `hsla(${hue}, 80%, 50%, 0)`);
      ctx.fillStyle = grd;
      ctx.fill();
    }
    const cGrad = ctx.createRadialGradient(cx, cy, 0, cx, cy, Math.max(1, Math.min(w, h) * 0.08));
    cGrad.addColorStop(0, "hsla(280, 100%, 80%, 0.6)");
    cGrad.addColorStop(1, "hsla(280, 100%, 50%, 0)");
    ctx.fillStyle = cGrad;
    ctx.beginPath();
    ctx.arc(cx, cy, Math.min(w, h) * 0.08, 0, Math.PI * 2);
    ctx.fill();
  }).current;
  const canvasRef = useCanvasDraw(drawFn);
  return <canvas ref={canvasRef} className={className} />;
}

function VisPreviewKaleidoscope({ className }: { className?: string }) {
  const drawFn = useRef((ctx: CanvasRenderingContext2D, w: number, h: number, dpr: number) => {
    ctx.fillStyle = "#000";
    ctx.fillRect(0, 0, w, h);
    const cx = w / 2, cy = h / 2;
    const folds = 6;
    for (let f = 0; f < folds; f++) {
      const baseAngle = (f / folds) * Math.PI * 2;
      for (let r = 3; r > 0; r--) {
        const radius = (r / 3) * Math.min(w, h) * 0.42;
        const hue = (f * 60 + r * 40 + 120) % 360;
        ctx.save();
        ctx.translate(cx, cy);
        ctx.rotate(baseAngle);
        ctx.beginPath();
        ctx.ellipse(0, radius * 0.4, radius * 0.18, radius * 0.5, 0, 0, Math.PI * 2);
        ctx.fillStyle = `hsla(${hue}, 75%, 50%, ${0.15 + r * 0.08})`;
        ctx.fill();
        ctx.restore();
      }
    }
    const grd = ctx.createRadialGradient(cx, cy, 0, cx, cy, Math.max(1, Math.min(w, h) * 0.15));
    grd.addColorStop(0, "hsla(300, 80%, 60%, 0.5)");
    grd.addColorStop(0.5, "hsla(240, 70%, 50%, 0.2)");
    grd.addColorStop(1, "hsla(240, 70%, 50%, 0)");
    ctx.fillStyle = grd;
    ctx.beginPath();
    ctx.arc(cx, cy, Math.min(w, h) * 0.15, 0, Math.PI * 2);
    ctx.fill();
    for (let r = 1; r <= 3; r++) {
      const radius = (r / 3) * Math.min(w, h) * 0.4;
      ctx.beginPath();
      ctx.arc(cx, cy, radius, 0, Math.PI * 2);
      ctx.strokeStyle = `hsla(${180 + r * 30}, 60%, 60%, 0.15)`;
      ctx.lineWidth = 1 * dpr;
      ctx.stroke();
    }
  }).current;
  const canvasRef = useCanvasDraw(drawFn);
  return <canvas ref={canvasRef} className={className} />;
}

function VisPreviewStarburst({ className }: { className?: string }) {
  const drawFn = useRef((ctx: CanvasRenderingContext2D, w: number, h: number, dpr: number) => {
    ctx.fillStyle = "#000";
    ctx.fillRect(0, 0, w, h);
    const cx = w / 2, cy = h / 2;
    const rayCount = 24;
    for (let i = 0; i < rayCount; i++) {
      const angle = (i / rayCount) * Math.PI * 2;
      const hue = (i / rayCount) * 360;
      const len = Math.min(w, h) * (0.35 + Math.sin(i * 1.3) * 0.12);
      const grad = ctx.createLinearGradient(cx, cy, cx + Math.cos(angle) * len, cy + Math.sin(angle) * len);
      grad.addColorStop(0, `hsla(${hue}, 90%, 70%, 0.8)`);
      grad.addColorStop(0.3, `hsla(${hue}, 85%, 55%, 0.4)`);
      grad.addColorStop(1, `hsla(${hue}, 80%, 40%, 0)`);
      ctx.beginPath();
      ctx.moveTo(cx, cy);
      const spread = 0.04;
      ctx.lineTo(cx + Math.cos(angle - spread) * len, cy + Math.sin(angle - spread) * len);
      ctx.lineTo(cx + Math.cos(angle + spread) * len, cy + Math.sin(angle + spread) * len);
      ctx.closePath();
      ctx.fillStyle = grad;
      ctx.fill();
    }
    const cGrad = ctx.createRadialGradient(cx, cy, 0, cx, cy, Math.max(1, Math.min(w, h) * 0.12));
    cGrad.addColorStop(0, "hsla(40, 100%, 95%, 0.9)");
    cGrad.addColorStop(0.3, "hsla(40, 100%, 70%, 0.5)");
    cGrad.addColorStop(1, "hsla(40, 90%, 50%, 0)");
    ctx.fillStyle = cGrad;
    ctx.beginPath();
    ctx.arc(cx, cy, Math.min(w, h) * 0.12, 0, Math.PI * 2);
    ctx.fill();
    for (let r = 1; r <= 3; r++) {
      const radius = (r / 3) * Math.min(w, h) * 0.38;
      ctx.beginPath();
      ctx.arc(cx, cy, radius, 0, Math.PI * 2);
      ctx.strokeStyle = `hsla(${30 + r * 30}, 70%, 55%, ${0.15 / r})`;
      ctx.lineWidth = 1.5 * dpr;
      ctx.stroke();
    }
  }).current;
  const canvasRef = useCanvasDraw(drawFn);
  return <canvas ref={canvasRef} className={className} />;
}

function VisPreviewSpiral({ className }: { className?: string }) {
  const drawFn = useRef((ctx: CanvasRenderingContext2D, w: number, h: number, dpr: number) => {
    ctx.fillStyle = "#000";
    ctx.fillRect(0, 0, w, h);
    const cx = w / 2, cy = h / 2;
    const arms = 3;
    for (let a = 0; a < arms; a++) {
      const armOffset = (a / arms) * Math.PI * 2;
      for (let t = 0; t < 600; t++) {
        const angle = t * 0.02 + armOffset;
        const r = t * 0.4;
        const x = cx + Math.cos(angle) * r;
        const y = cy + Math.sin(angle) * r;
        if (x < -10 || x > w + 10 || y < -10 || y > h + 10) continue;
        const hue = (t * 0.5 + a * 120) % 360;
        const alpha = Math.max(0, 0.5 - r / Math.min(w, h));
        ctx.fillStyle = `hsla(${hue}, 70%, 55%, ${alpha})`;
        ctx.beginPath();
        ctx.arc(x, y, 1.5 * dpr, 0, Math.PI * 2);
        ctx.fill();
      }
    }
    for (let i = 0; i < 60; i++) {
      const angle = Math.random() * Math.PI * 2;
      const r = Math.random() * Math.min(w, h) * 0.45;
      const x = cx + Math.cos(angle) * r;
      const y = cy + Math.sin(angle) * r;
      ctx.beginPath();
      ctx.arc(x, y, (0.5 + Math.random()) * dpr, 0, Math.PI * 2);
      ctx.fillStyle = `hsla(0, 0%, 100%, ${0.2 + Math.random() * 0.4})`;
      ctx.fill();
    }
    const grd = ctx.createRadialGradient(cx, cy, 0, cx, cy, Math.max(1, Math.min(w, h) * 0.08));
    grd.addColorStop(0, "hsla(40, 80%, 80%, 0.6)");
    grd.addColorStop(1, "hsla(40, 80%, 50%, 0)");
    ctx.fillStyle = grd;
    ctx.beginPath();
    ctx.arc(cx, cy, Math.min(w, h) * 0.08, 0, Math.PI * 2);
    ctx.fill();
  }).current;
  const canvasRef = useCanvasDraw(drawFn);
  return <canvas ref={canvasRef} className={className} />;
}

function VisPreviewFractal({ className }: { className?: string }) {
  const drawFn = useRef((ctx: CanvasRenderingContext2D, w: number, h: number, _dpr: number) => {
    const cR = -0.7, cI = 0.27015;
    const imgData = ctx.createImageData(w, h);
    const maxIter = 40;
    for (let px = 0; px < w; px++) {
      for (let py = 0; py < h; py++) {
        let zr = (px / w - 0.5) * 3;
        let zi = (py / h - 0.5) * 3;
        let iter = 0;
        while (zr * zr + zi * zi < 4 && iter < maxIter) {
          const tmp = zr * zr - zi * zi + cR;
          zi = 2 * zr * zi + cI;
          zr = tmp;
          iter++;
        }
        const idx = (py * w + px) * 4;
        if (iter === maxIter) {
          imgData.data[idx] = 5;
          imgData.data[idx + 1] = 5;
          imgData.data[idx + 2] = 15;
        } else {
          const t = iter / maxIter;
          const hue = t * 360;
          const s = 0.85, l = t * 0.6;
          const c = (1 - Math.abs(2 * l - 1)) * s;
          const x = c * (1 - Math.abs((hue / 60) % 2 - 1));
          const m = l - c / 2;
          let r = 0, g = 0, b = 0;
          if (hue < 60) { r = c; g = x; }
          else if (hue < 120) { r = x; g = c; }
          else if (hue < 180) { g = c; b = x; }
          else if (hue < 240) { g = x; b = c; }
          else if (hue < 300) { r = x; b = c; }
          else { r = c; b = x; }
          imgData.data[idx] = (r + m) * 255;
          imgData.data[idx + 1] = (g + m) * 255;
          imgData.data[idx + 2] = (b + m) * 255;
        }
        imgData.data[idx + 3] = 255;
      }
    }
    ctx.putImageData(imgData, 0, 0);
  }).current;
  const canvasRef = useCanvasDraw(drawFn);
  return <canvas ref={canvasRef} className={className} />;
}

// ── Visualization preview card (canvas-based) ────────────────────────────
function VisCard({
  name,
  children,
}: {
  name: string;
  children: React.ReactNode;
}) {
  return (
    <div className="group relative overflow-hidden rounded-xl border border-zinc-800/50 transition-all duration-300 hover:border-zinc-700/80 hover:shadow-lg hover:shadow-purple-500/10">
      <div className="aspect-video w-full bg-black relative">
        {children}
      </div>
      <div className="absolute inset-0 flex items-end bg-gradient-to-t from-black/70 via-transparent to-transparent p-3">
        <span className="text-xs font-medium text-zinc-300">{name}</span>
      </div>
    </div>
  );
}

// ── Scrolling visualizer names ───────────────────────────────────────────
function VisModes() {
  const modes = [
    "EQ Bars",
    "Oscilloscope",
    "Radial",
    "Tunnel",
    "Kaleidoscope",
    "Warp Grid",
    "Honeycomb",
    "Diamond",
    "Starburst",
    "Spiral",
    "Liquid",
    "Fractal",
  ];

  return (
    <div className="flex flex-wrap justify-center gap-2">
      {modes.map((mode) => (
        <span
          key={mode}
          className="rounded-full border border-zinc-700/50 bg-zinc-800/50 px-3 py-1 text-xs font-medium text-zinc-400 transition-colors hover:border-purple-500/30 hover:text-purple-300"
        >
          {mode}
        </span>
      ))}
    </div>
  );
}

// ── Phone mockup with swipeable screenshots ─────────────────────────────
const screenshots = [
  { src: "/android-screenshot-playing.png", label: "Now Playing" },
  { src: "/android-screenshot-home.png", label: "Home" },
  { src: "/android-screenshot-library.png", label: "Library" },
];

function PhoneMockup() {
  const [current, setCurrent] = useState(0);
  const touchStartX = useRef(0);
  const touchDeltaX = useRef(0);
  const isDragging = useRef(false);
  const [dragOffset, setDragOffset] = useState(0);

  function handlePointerDown(e: React.PointerEvent) {
    touchStartX.current = e.clientX;
    touchDeltaX.current = 0;
    isDragging.current = true;
    (e.target as HTMLElement).setPointerCapture(e.pointerId);
  }

  function handlePointerMove(e: React.PointerEvent) {
    if (!isDragging.current) return;
    touchDeltaX.current = e.clientX - touchStartX.current;
    setDragOffset(touchDeltaX.current);
  }

  function handlePointerUp() {
    if (!isDragging.current) return;
    isDragging.current = false;
    const threshold = 40;
    if (touchDeltaX.current < -threshold && current < screenshots.length - 1) {
      setCurrent(current + 1);
    } else if (touchDeltaX.current > threshold && current > 0) {
      setCurrent(current - 1);
    }
    setDragOffset(0);
  }

  return (
    <div className="relative w-[260px]">
      {/* Phone frame */}
      <div className="overflow-hidden rounded-[2.5rem] border-[3px] border-zinc-700/60 bg-black shadow-2xl shadow-black/60">
        {/* Status bar / notch area */}
        <div className="flex h-7 items-center justify-center bg-black">
          <div className="h-1 w-16 rounded-full bg-zinc-800" />
        </div>

        {/* Screenshot carousel */}
        <div
          className="relative overflow-hidden bg-black select-none touch-pan-y"
          style={{ aspectRatio: "9 / 19.5" }}
          onPointerDown={handlePointerDown}
          onPointerMove={handlePointerMove}
          onPointerUp={handlePointerUp}
          onPointerCancel={handlePointerUp}
        >
          <div
            className="flex h-full transition-transform duration-300 ease-out"
            style={{
              width: `${screenshots.length * 100}%`,
              transform: `translateX(calc(-${(current * 100) / screenshots.length}% + ${dragOffset}px))`,
              transitionDuration: isDragging.current ? "0ms" : "300ms",
            }}
          >
            {screenshots.map((s) => (
              <img
                key={s.src}
                src={s.src}
                alt={s.label}
                className="h-full object-cover object-top pointer-events-none"
                style={{ width: `${100 / screenshots.length}%` }}
                draggable={false}
              />
            ))}
          </div>
        </div>

        {/* Bottom gesture bar */}
        <div className="flex h-5 items-center justify-center bg-black">
          <div className="h-0.5 w-16 rounded-full bg-zinc-700" />
        </div>
      </div>

      {/* Dot indicators */}
      <div className="mt-4 flex items-center justify-center gap-2">
        {screenshots.map((s, i) => (
          <button
            key={s.src}
            onClick={() => setCurrent(i)}
            className={`h-1.5 rounded-full transition-all duration-300 ${
              i === current
                ? "w-6 bg-[#00ff00]"
                : "w-1.5 bg-zinc-600 hover:bg-zinc-500"
            }`}
            aria-label={s.label}
          />
        ))}
      </div>
      <p className="mt-2 text-center text-xs text-zinc-500">
        {screenshots[current]?.label}
      </p>
    </div>
  );
}

// ── Main Landing Page ────────────────────────────────────────────────────
export function LandingPage() {
  const [scrollY, setScrollY] = useState(0);

  useEffect(() => {
    const onScroll = () => setScrollY(window.scrollY);
    window.addEventListener("scroll", onScroll, { passive: true });
    return () => window.removeEventListener("scroll", onScroll);
  }, []);

  return (
    <div className="min-h-screen bg-zinc-950 text-zinc-100 antialiased">
      {/* ── Navigation ──────────────────────────────────────────────── */}
      <nav
        className={`fixed top-0 z-50 w-full transition-all duration-300 ${
          scrollY > 20
            ? "border-b border-zinc-800/60 bg-zinc-950/80 backdrop-blur-xl"
            : "bg-transparent"
        }`}
      >
        <div className="mx-auto flex max-w-6xl items-center justify-between px-6 py-4">
          <div className="flex items-center gap-2.5">
            <CloudAmpLogo className="h-8 w-8" />
            <span className="text-xl font-bold tracking-tight">CloudAmp</span>
          </div>
          <div className="flex items-center gap-3">
            <a
              href="/app"
              className="rounded-lg bg-blue-600 px-5 py-2 text-sm font-semibold text-white transition-all hover:bg-blue-500 hover:shadow-lg hover:shadow-blue-500/25"
            >
              Open App
            </a>
          </div>
        </div>
      </nav>

      {/* ── Hero ────────────────────────────────────────────────────── */}
      <section className="relative flex min-h-screen flex-col items-center justify-center overflow-hidden px-6 pt-20">
        {/* Animated background */}
        <div className="absolute inset-0">
          <AnimatedEQBars />
          <div className="absolute inset-0 bg-gradient-to-b from-zinc-950/40 via-zinc-950/80 to-zinc-950" />
        </div>

        {/* Subtle grid pattern */}
        <div
          className="absolute inset-0 opacity-[0.03]"
          style={{
            backgroundImage:
              "linear-gradient(rgba(255,255,255,0.1) 1px, transparent 1px), linear-gradient(90deg, rgba(255,255,255,0.1) 1px, transparent 1px)",
            backgroundSize: "60px 60px",
          }}
        />

        <div className="relative z-10 mx-auto max-w-4xl text-center">
          <div className="mb-6 inline-flex items-center gap-2 rounded-full border border-zinc-700/50 bg-zinc-800/50 px-4 py-1.5 text-sm text-zinc-400 backdrop-blur-sm">
            <span className="inline-block h-1.5 w-1.5 rounded-full bg-green-500" />
            100% Free &middot; No Ads &middot; No Account Required
          </div>

          <h1 className="mb-6 text-5xl font-extrabold leading-[1.1] tracking-tight sm:text-6xl md:text-7xl">
            Your music.{" "}
            <span className="bg-gradient-to-r from-blue-400 via-purple-400 to-pink-400 bg-clip-text text-transparent">
              Your drive.
            </span>
            <br />
            Your player.
          </h1>

          <p className="mx-auto mb-10 max-w-2xl text-lg text-zinc-400 sm:text-xl">
            CloudAmp is a retro-inspired music player that streams directly from
            your Google Drive. Winamp vibes meet modern cloud streaming — with
            stunning visualizations.
          </p>

          <div className="flex flex-col items-center gap-4 sm:flex-row sm:justify-center">
            <a
              href="/app"
              className="group flex items-center gap-2 rounded-xl bg-blue-600 px-8 py-3.5 text-base font-semibold text-white shadow-lg shadow-blue-500/20 transition-all hover:bg-blue-500 hover:shadow-xl hover:shadow-blue-500/30"
            >
              <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className="transition-transform group-hover:translate-x-0.5">
                <polygon points="5 3 19 12 5 21 5 3" fill="currentColor" />
              </svg>
              Launch Web App
            </a>
            <a
              href="#features"
              className="flex items-center gap-2 rounded-xl border border-zinc-700 bg-zinc-900/50 px-8 py-3.5 text-base font-semibold text-zinc-300 transition-all hover:border-zinc-600 hover:bg-zinc-800/80 hover:text-white"
            >
              Learn More
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                <path d="M12 5v14M19 12l-7 7-7-7" />
              </svg>
            </a>
          </div>
        </div>

        {/* Scroll indicator */}
        <div className="absolute bottom-8 left-1/2 -translate-x-1/2 animate-bounce">
          <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" className="text-zinc-600">
            <path d="M12 5v14M19 12l-7 7-7-7" strokeLinecap="round" strokeLinejoin="round" />
          </svg>
        </div>
      </section>

      {/* ── Features ────────────────────────────────────────────────── */}
      <section id="features" className="relative px-6 py-24">
        <div className="mx-auto max-w-6xl">
          <div className="mb-16 text-center">
            <h2 className="mb-4 text-3xl font-bold tracking-tight sm:text-4xl">
              Everything you need. Nothing you don't.
            </h2>
            <p className="mx-auto max-w-2xl text-zinc-400">
              No uploads, no subscriptions, no data harvesting. Just connect
              your Google Drive and press play.
            </p>
          </div>

          <div className="grid gap-6 sm:grid-cols-2 lg:grid-cols-3">
            <FeatureCard
              icon={
                <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round">
                  <path d="M15 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V7Z" />
                  <path d="M14 2v4a2 2 0 0 0 2 2h4" />
                  <circle cx="10" cy="16" r="2" />
                  <path d="M12 12v4" />
                  <path d="m15 12-3 2-3-2" />
                </svg>
              }
              title="Google Drive Streaming"
              description="Stream music directly from your Google Drive. Your files stay yours — no uploads, no copies, no storage limits imposed by us."
            />
            <FeatureCard
              icon={
                <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round">
                  <rect x="2" y="3" width="20" height="14" rx="2" />
                  <path d="M8 21h8" />
                  <path d="M12 17v4" />
                  <path d="M6 8h.01M6 11h.01M6 14h.01" />
                  <path d="M9 8h6M9 11h6M9 14h3" />
                </svg>
              }
              title="Winamp-Inspired Android App"
              description="A native Android app with classic Winamp aesthetics. Retro EQ bars, familiar controls, and that nostalgic music player feel — rebuilt for modern Android."
            />
            <FeatureCard
              icon={
                <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round">
                  <circle cx="12" cy="12" r="10" />
                  <path d="M12 2a15.3 15.3 0 0 1 4 10 15.3 15.3 0 0 1-4 10 15.3 15.3 0 0 1-4-10 15.3 15.3 0 0 1 4-10z" />
                </svg>
              }
              title="12 Visualizations"
              description="From classic EQ bars and oscilloscopes to WebGL-powered fractals, spirals, and kaleidoscopes. Your music has never looked this good."
            />
            <FeatureCard
              icon={
                <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round">
                  <path d="M12 22s-8-4.5-8-11.8A8 8 0 0 1 12 2a8 8 0 0 1 8 8.2c0 7.3-8 11.8-8 11.8z" />
                  <circle cx="12" cy="10" r="3" />
                </svg>
              }
              title="Completely Free"
              description="No premium tiers, no trial periods, no ads. CloudAmp is free to use, forever. Your music library, your way."
            />
            <FeatureCard
              icon={
                <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round">
                  <rect x="5" y="2" width="14" height="20" rx="2" ry="2" />
                  <path d="M12 18h.01" />
                </svg>
              }
              title="Android Auto Support"
              description="Control your music hands-free while driving. CloudAmp integrates with Android Auto for safe, distraction-free playback."
            />
            <FeatureCard
              icon={
                <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round">
                  <path d="M9 18V5l12-2v13" />
                  <circle cx="6" cy="18" r="3" />
                  <circle cx="18" cy="16" r="3" />
                </svg>
              }
              title="Smart Library Scanner"
              description="Automatic music library scanning with metadata enrichment. Album art, artist info, and smart organization — all from your Drive files."
            />
          </div>
        </div>
      </section>

      {/* ── Visualizations showcase ─────────────────────────────────── */}
      <section className="relative px-6 py-24">
        {/* Background glow */}
        <div className="pointer-events-none absolute left-1/2 top-1/2 -translate-x-1/2 -translate-y-1/2">
          <div className="h-[500px] w-[500px] rounded-full bg-purple-500/5 blur-[120px]" />
        </div>

        <div className="relative mx-auto max-w-6xl">
          <div className="mb-16 text-center">
            <h2 className="mb-4 text-3xl font-bold tracking-tight sm:text-4xl">
              Visualizations that move with your music
            </h2>
            <p className="mx-auto mb-8 max-w-2xl text-zinc-400">
              12 audio-reactive visualization modes powered by Canvas 2D and
              WebGL. From classic EQ bars to mesmerizing fractal tunnels.
            </p>
            <VisModes />
          </div>

          <div className="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-4">
            <VisCard name="EQ Bars">
              <VisPreviewEQ className="absolute inset-0 w-full h-full" />
            </VisCard>
            <VisCard name="Oscilloscope">
              <VisPreviewOscilloscope className="absolute inset-0 w-full h-full" />
            </VisCard>
            <VisCard name="Radial">
              <VisPreviewRadial className="absolute inset-0 w-full h-full" />
            </VisCard>
            <VisCard name="Tunnel">
              <VisPreviewTunnel className="absolute inset-0 w-full h-full" />
            </VisCard>
            <VisCard name="Kaleidoscope">
              <VisPreviewKaleidoscope className="absolute inset-0 w-full h-full" />
            </VisCard>
            <VisCard name="Starburst">
              <VisPreviewStarburst className="absolute inset-0 w-full h-full" />
            </VisCard>
            <VisCard name="Spiral">
              <VisPreviewSpiral className="absolute inset-0 w-full h-full" />
            </VisCard>
            <VisCard name="Fractal">
              <VisPreviewFractal className="absolute inset-0 w-full h-full" />
            </VisCard>
          </div>
        </div>
      </section>

      {/* ── How it works ────────────────────────────────────────────── */}
      <section className="relative px-6 py-24">
        <div className="mx-auto max-w-4xl">
          <div className="mb-16 text-center">
            <h2 className="mb-4 text-3xl font-bold tracking-tight sm:text-4xl">
              Three steps. That's it.
            </h2>
            <p className="mx-auto max-w-2xl text-zinc-400">
              No sign-ups, no installations (for the web app), no credit cards.
            </p>
          </div>

          <div className="grid gap-8 sm:grid-cols-3">
            {[
              {
                step: "1",
                title: "Connect Google Drive",
                desc: "Sign in with Google and pick the folders with your music files.",
              },
              {
                step: "2",
                title: "Scan your library",
                desc: "CloudAmp scans your Drive for audio files and fetches album art and metadata automatically.",
              },
              {
                step: "3",
                title: "Press play",
                desc: "Browse your library, pick a track, and enjoy — with 12 real-time visualizations.",
              },
            ].map((item) => (
              <div key={item.step} className="text-center">
                <div className="mx-auto mb-4 flex h-12 w-12 items-center justify-center rounded-full bg-gradient-to-br from-blue-500 to-purple-600 text-lg font-bold text-white shadow-lg shadow-blue-500/20">
                  {item.step}
                </div>
                <h3 className="mb-2 text-lg font-semibold text-white">
                  {item.title}
                </h3>
                <p className="text-sm text-zinc-400">{item.desc}</p>
              </div>
            ))}
          </div>
        </div>
      </section>

      {/* ── Android App ─────────────────────────────────────────────── */}
      <section className="relative px-6 py-24">
        <div className="mx-auto max-w-5xl">
          <div className="overflow-hidden rounded-2xl border border-zinc-800/60 bg-zinc-900/30">
            <div className="grid items-center gap-8 p-8 sm:grid-cols-2 sm:p-12">
              <div>
                <div className="mb-4 inline-flex items-center gap-2 rounded-full border border-zinc-700/50 bg-zinc-800/50 px-3 py-1 text-xs font-medium text-zinc-400">
                  <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                    <rect x="5" y="2" width="14" height="20" rx="2" ry="2" />
                    <path d="M12 18h.01" />
                  </svg>
                  Android App
                </div>
                <h3 className="mb-3 text-2xl font-bold tracking-tight text-white sm:text-3xl">
                  Winamp nostalgia,{" "}
                  <span className="text-[#00ff00]">reimagined</span>
                </h3>
                <p className="mb-6 text-zinc-400">
                  The CloudAmp Android app brings back the golden age of music
                  players. Neon green monospace text on black, classic transport controls, and
                  a retro digital display — all streaming from your Google Drive.
                </p>
                <ul className="space-y-2 text-sm text-zinc-400">
                  {[
                    "Monospace green-on-black Winamp aesthetic",
                    "64-bar log-spaced EQ visualizer",
                    "Android Auto support",
                    "Background playback with media controls",
                    "Queue management & shuffle",
                  ].map((item) => (
                    <li key={item} className="flex items-start gap-2">
                      <svg width="16" height="16" viewBox="0 0 24 24" fill="none" className="mt-0.5 shrink-0 text-[#00ff00]">
                        <path d="M20 6L9 17l-5-5" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" />
                      </svg>
                      {item}
                    </li>
                  ))}
                </ul>
              </div>
              <div className="flex justify-center">
                <PhoneMockup />
              </div>
            </div>
          </div>
        </div>
      </section>

      {/* ── CTA ─────────────────────────────────────────────────────── */}
      <section className="relative px-6 py-32">
        <div className="pointer-events-none absolute left-1/2 top-1/2 -translate-x-1/2 -translate-y-1/2">
          <div className="h-[400px] w-[600px] rounded-full bg-blue-500/5 blur-[100px]" />
        </div>
        <div className="relative mx-auto max-w-3xl text-center">
          <h2 className="mb-4 text-3xl font-bold tracking-tight sm:text-4xl md:text-5xl">
            Ready to play?
          </h2>
          <p className="mx-auto mb-8 max-w-xl text-lg text-zinc-400">
            Connect your Google Drive and start streaming your music library in
            seconds. No sign-up needed.
          </p>
          <a
            href="/app"
            className="inline-flex items-center gap-2 rounded-xl bg-blue-600 px-10 py-4 text-lg font-semibold text-white shadow-lg shadow-blue-500/20 transition-all hover:bg-blue-500 hover:shadow-xl hover:shadow-blue-500/30"
          >
            <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
              <polygon points="5 3 19 12 5 21 5 3" fill="currentColor" />
            </svg>
            Launch CloudAmp
          </a>
        </div>
      </section>

      {/* ── Footer ──────────────────────────────────────────────────── */}
      <footer className="border-t border-zinc-800/60 px-6 py-8">
        <div className="mx-auto flex max-w-6xl flex-col items-center justify-between gap-4 sm:flex-row">
          <div className="flex items-center gap-2 text-zinc-500">
            <CloudAmpLogo className="h-5 w-5 opacity-50" />
            <span className="text-sm">CloudAmp</span>
          </div>
          <p className="text-xs text-zinc-600">
            Your music, streamed from Google Drive. Free forever.
          </p>
        </div>
      </footer>
    </div>
  );
}
