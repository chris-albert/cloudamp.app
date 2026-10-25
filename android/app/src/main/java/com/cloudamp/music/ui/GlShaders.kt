package com.cloudamp.music.ui

/**
 * GLSL ES 3.00 shaders for the feedback-based visualizations, ported verbatim
 * from the web app (web/src/routes/visualizer.tsx). Each fragment shader warps
 * the previous frame (u_prev) and injects new audio-reactive content from a
 * 256x1 log-remapped frequency texture (u_freq).
 */
object GlShaders {

    const val FULLSCREEN_QUAD_VS = """#version 300 es
in vec2 a_position;
out vec2 v_uv;
void main() {
  v_uv = a_position * 0.5 + 0.5;
  gl_Position = vec4(a_position, 0.0, 1.0);
}"""

    const val TUNNEL_FS = """#version 300 es
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
}"""

    const val KALEIDOSCOPE_FS = """#version 300 es
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
}"""

    const val WARPGRID_FS = """#version 300 es
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
}"""

    const val HONEYCOMB_FS = """#version 300 es
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
}"""

    const val DIAMOND_FS = """#version 300 es
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
}"""

    const val STARBURST_FS = """#version 300 es
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
}"""

    const val SPIRAL_FS = """#version 300 es
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
}"""

    const val LIQUID_FS = """#version 300 es
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
}"""

    const val FRACTAL_FS = """#version 300 es
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
}"""
}
