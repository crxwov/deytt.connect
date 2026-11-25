"use strict";

function createDockOptics(canvas) {
  const gl = canvas.getContext("webgl2", { depth: false, antialias: false });
  if (!gl) return null;

  const vertexSource = '#version 300 es\n'
    + 'void main() { gl_Position = vec4((gl_VertexID & 1) * 2 - 1, (gl_VertexID >> 1) * 2 - 1, 0, 1); }';
  const fragmentSource = `#version 300 es
    precision highp float;
    uniform vec2 resolution;
    uniform float pixelRatio;
    uniform vec4 lens;
    uniform bool showReflection;
    uniform bool showOutside;
    uniform float displacementScale;
    uniform sampler2D backdrop;
    out vec4 fragColor;

    float roundedBox(vec2 point, vec2 halfSize, float radius) {
      vec2 q = abs(point) - halfSize + radius;
      return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - radius;
    }

    float surfaceHeight(vec2 point, vec2 halfSize, float radius) {
      float bevelWidth = halfSize.y * 0.64;
      float edge = clamp(1.0 + roundedBox(point, halfSize, radius) / bevelWidth, 0.0, 1.0);
      // A rounded shoulder and a shallow dome meet with a continuous slope.
      float arc = sqrt(1.0 - 0.88 * edge * edge);
      vec2 normalized = point / halfSize;
      float dome = 1.0 - 0.5 * dot(normalized, normalized);
      return halfSize.y * (0.30 + 0.52 * (arc - sqrt(0.12)) + 0.28 * dome);
    }

    vec4 sampleBackdrop(vec2 cssPoint) {
      return texture(backdrop, cssPoint * pixelRatio / resolution);
    }

    void main() {
      vec2 point = vec2(gl_FragCoord.x, resolution.y - gl_FragCoord.y) / pixelRatio;
      vec2 local = point - lens.xy;
      vec2 halfSize = lens.zw;
      float radius = min(halfSize.x, halfSize.y) * 0.94;
      float distanceToEdge = roundedBox(local, halfSize, radius);
      float coverage = 1.0 - smoothstep(-0.5 / pixelRatio, 0.5 / pixelRatio, distanceToEdge);
      vec4 background = showOutside ? sampleBackdrop(point) : vec4(0.0);
      if (coverage == 0.0) {
        fragColor = displacementScale > 0.0 ? vec4(0.5, 0.5, 0.0, 1.0) : background;
        return;
      }

      const float ior = 1.50;
      const vec3 incident = vec3(0.0, 0.0, -1.0);
      const vec3 planeNormal = vec3(0.0, 0.0, 1.0);
      const float stepSize = 0.18;
      const vec2 dx = vec2(stepSize, 0.0);
      const vec2 dy = vec2(0.0, stepSize);
      vec2 gradient = vec2(
        surfaceHeight(local + dx, halfSize, radius) - surfaceHeight(local - dx, halfSize, radius),
        surfaceHeight(local + dy, halfSize, radius) - surfaceHeight(local - dy, halfSize, radius)
      ) / (2.0 * stepSize);
      vec3 normal = normalize(vec3(-gradient, 1.0));

      // Trace backwards from the viewer: curved air/glass entrance, flat glass/air exit.
      vec3 inGlass = refract(incident, normal, 1.0 / ior);
      vec3 outGlass = refract(inGlass, planeNormal, ior);
      float height = surfaceHeight(local, halfSize, radius);
      float backdropGap = halfSize.y * 0.44;
      vec2 atBase = point + inGlass.xy * height / -inGlass.z;
      vec2 atBackdrop = atBase + outGlass.xy * backdropGap / max(0.001, -outGlass.z);
      if (displacementScale > 0.0) {
        vec2 channels = 0.5 + (atBackdrop - point) / displacementScale;
        fragColor = vec4(channels, 0.0, 1.0);
        return;
      }
      vec4 transmitted = sampleBackdrop(atBackdrop);
      if (showReflection) {
        vec3 ray = reflect(incident, normal);
        float key = pow(max(dot(ray, normalize(vec3(-0.71, -0.51, 0.48))), 0.0), 17.0);
        float fill = pow(max(dot(ray, normalize(vec3(0.61, 0.59, 0.52))), 0.0), 21.0);
        float fresnel = 0.04 + 0.96 * pow(1.0 - normal.z, 5.0);
        float reflected = fresnel * (2.8 * key + 1.8 * fill);
        transmitted = mix(transmitted, vec4(1.0), reflected);
      }
      fragColor = mix(background, transmitted, coverage);
    }`;

  function compile(type, source) {
    const shader = gl.createShader(type);
    gl.shaderSource(shader, source);
    gl.compileShader(shader);
    if (!gl.getShaderParameter(shader, gl.COMPILE_STATUS)) {
      console.error("dock optics:", gl.getShaderInfoLog(shader));
      gl.deleteShader(shader);
      return null;
    }
    return shader;
  }

  const vertex = compile(gl.VERTEX_SHADER, vertexSource);
  const fragment = compile(gl.FRAGMENT_SHADER, fragmentSource);
  if (!vertex || !fragment) return null;
  const program = gl.createProgram();
  gl.attachShader(program, vertex);
  gl.attachShader(program, fragment);
  gl.linkProgram(program);
  gl.deleteShader(vertex);
  gl.deleteShader(fragment);
  if (!gl.getProgramParameter(program, gl.LINK_STATUS)) return null;

  const texture = gl.createTexture();
  gl.bindTexture(gl.TEXTURE_2D, texture);
  gl.pixelStorei(gl.UNPACK_PREMULTIPLY_ALPHA_WEBGL, true);
  gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR);
  gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
  gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
  gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);

  const resolution = gl.getUniformLocation(program, "resolution");
  const pixelRatio = gl.getUniformLocation(program, "pixelRatio");
  const lens = gl.getUniformLocation(program, "lens");
  const showReflection = gl.getUniformLocation(program, "showReflection");
  const showOutside = gl.getUniformLocation(program, "showOutside");
  const displacementScale = gl.getUniformLocation(program, "displacementScale");

  return {
    gl,
    draw(source, ratio, bounds, reflection, outside = false, mapScale = 0) {
      gl.viewport(0, 0, canvas.width, canvas.height);
      gl.useProgram(program);
      if (source) {
        gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, gl.RGBA, gl.UNSIGNED_BYTE, source);
      } else {
        // The map pass returns before sampling, but WebGL still requires a complete sampler.
        gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, 1, 1, 0, gl.RGBA, gl.UNSIGNED_BYTE, null);
      }
      gl.uniform2f(resolution, canvas.width, canvas.height);
      gl.uniform1f(pixelRatio, ratio);
      gl.uniform4fv(lens, bounds);
      gl.uniform1i(showReflection, reflection);
      gl.uniform1i(showOutside, outside);
      gl.uniform1f(displacementScale, mapScale);
      gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4);
    }
  };
}


function createDockBackdropFilter(surface, image, displacement) {
  const canvas = document.createElement("canvas");
  let renderer = createDockOptics(canvas);
  if (!renderer) return null;

  const maps = new Map();
  let currentKey;
  canvas.addEventListener("webglcontextlost", event => event.preventDefault());
  canvas.addEventListener("webglcontextrestored", () => { renderer = createDockOptics(canvas); });

  return (centerX, centerY, width, height, ratio) => {
    if (!renderer || renderer.gl.isContextLost()) return false;

    const key = [width, height, ratio].join("/");
    if (currentKey !== key) {
      let map = maps.get(key);
      if (!map) {
        canvas.width = Math.round(width * ratio);
        canvas.height = Math.round(height * ratio);
        const scale = height * 2;
        renderer.draw(null, ratio, [width / 2, height / 2, width / 2, height / 2], false, false, scale);
        map = { url: canvas.toDataURL(), scale };
        if (maps.size === 24) maps.delete(maps.keys().next().value);
        maps.set(key, map);
      }
      displacement.setAttribute("scale", map.scale);
      image.setAttribute("href", map.url);
      surface.style.width = width + "px";
      surface.style.height = height + "px";
      surface.style.borderRadius = Math.min(width, height) * 0.47 + "px";
      currentKey = key;
    }
    surface.style.transform = "translate(" + (centerX - width / 2) + "px, " + (centerY - height / 2) + "px)";
    return true;
  };
}
