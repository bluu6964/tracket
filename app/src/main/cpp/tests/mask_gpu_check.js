// Runs the REAL mask shaders (extracted from engine/renderer_effects.cpp) in a headless Chromium (WebGL2 = GLES 3.0
// GLSL) and compares them with the CPU reference dump of mask_plan_selftest.cpp.
//   g++ -std=c++17 -I../engine mask_plan_selftest.cpp ../engine/mask_plan.cpp -o t && ./t dump cpu.txt
//   node mask_gpu_check.js cpu.txt            (needs `playwright`; set NODE_PATH if it is installed globally)
const fs = require('fs');
const path = require('path');
const { chromium } = require('playwright');

function extract(name) {
  const src = fs.readFileSync(path.join(__dirname, '../engine/renderer_effects.cpp'), 'utf8');
  const i = src.indexOf('const char* ' + name + ' =');
  if (i < 0) throw new Error('shader ' + name + ' not found');
  const rest = src.slice(i);
  let out = '';
  const head = rest.match(/=\s*("#version 300 es\\n"\s*)?R"\(/);
  let body = rest.slice(head.index + head[0].length);
  body = body.slice(0, body.indexOf(')";'));
  return (head[1] ? '#version 300 es\n' : '') + body;
}
function vertex() {
  const src = fs.readFileSync(path.join(__dirname, '../engine/renderer_effects.cpp'), 'utf8');
  const m = src.match(/const char\* kVert = R"\(([\s\S]*?)\)";/);
  return m[1];
}

function parse(file) {
  const lines = fs.readFileSync(file, 'utf8').split('\n');
  const cfgs = [];
  let i = 0;
  while (i < lines.length && lines[i].trim()) {
    const [name, w, h, n] = lines[i++].split(' ');
    const ops = [];
    for (let k = 0; k < +n; k++) {
      const v = lines[i++].trim().split(/\s+/).map(Number);
      ops.push({ shape: v[0], mode: v[1], invert: v[2], cx: v[3], cy: v[4], hw: v[5], hh: v[6], cos: v[7], sin: v[8],
        feather: v[9], expand: v[10], opacity: v[11], corner: v[12], an: v[13], anCos: v[14], anSin: v[15],
        enCos: v[16], enSin: v[17], nPts: v[18], pts: v.slice(19) });
    }
    const grid = lines[i++].trim().split(/\s+/).map(Number);
    cfgs.push({ name, w: +w, h: +h, ops, grid });
  }
  return cfgs;
}

(async () => {
  const cfgs = parse(process.argv[2]);
  const browser = await chromium.launch({ args: ['--use-gl=angle', '--use-angle=swiftshader', '--enable-unsafe-swiftshader', '--ignore-gpu-blocklist'] });
  const page = await browser.newPage();
  await page.setContent('<canvas id=c width=4 height=4></canvas>');
  const res = await page.evaluate(async ({ cfgs, vs, kMask, kApply }) => {
    const gl = document.getElementById('c').getContext('webgl2');
    if (!gl) return { error: 'no webgl2' };
    function sh(type, src) {
      const s = gl.createShader(type); gl.shaderSource(s, src); gl.compileShader(s);
      if (!gl.getShaderParameter(s, gl.COMPILE_STATUS)) throw new Error('compile: ' + gl.getShaderInfoLog(s));
      return s;
    }
    function prog(fs) {
      const p = gl.createProgram(); gl.attachShader(p, sh(gl.VERTEX_SHADER, vs)); gl.attachShader(p, sh(gl.FRAGMENT_SHADER, fs));
      gl.linkProgram(p);
      if (!gl.getProgramParameter(p, gl.LINK_STATUS)) throw new Error('link: ' + gl.getProgramInfoLog(p));
      return p;
    }
    const pm = prog(kMask), pa = prog(kApply);
    const vb = gl.createBuffer(); gl.bindBuffer(gl.ARRAY_BUFFER, vb);
    gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-0.5, -0.5, 0.5, -0.5, -0.5, 0.5, 0.5, 0.5]), gl.STATIC_DRAW);
    gl.enableVertexAttribArray(0); gl.vertexAttribPointer(0, 2, gl.FLOAT, false, 0, 0);
    function target(w, h, fmt) {
      const t = gl.createTexture(); gl.bindTexture(gl.TEXTURE_2D, t);
      if (fmt === 'r8') gl.texImage2D(gl.TEXTURE_2D, 0, gl.R8, w, h, 0, gl.RED, gl.UNSIGNED_BYTE, null);
      else gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA8, w, h, 0, gl.RGBA, gl.UNSIGNED_BYTE, null);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR); gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE); gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
      const f = gl.createFramebuffer(); gl.bindFramebuffer(gl.FRAMEBUFFER, f);
      gl.framebufferTexture2D(gl.FRAMEBUFFER, gl.COLOR_ATTACHMENT0, gl.TEXTURE_2D, t, 0);
      if (gl.checkFramebufferStatus(gl.FRAMEBUFFER) !== gl.FRAMEBUFFER_COMPLETE) throw new Error('fbo incomplete ' + fmt);
      return { t, f };
    }
    const out = [];
    for (const c of cfgs) {
      const A = [target(c.w, c.h, 'r8'), target(c.w, c.h, 'r8')];
      const init = (c.ops.length && (c.ops[0].mode === 0 || c.ops[0].mode === 3)) ? 0 : 1;
      gl.bindFramebuffer(gl.FRAMEBUFFER, A[0].f); gl.viewport(0, 0, c.w, c.h); gl.clearColor(init, 0, 0, 0); gl.clear(gl.COLOR_BUFFER_BIT);
      let a = 0;
      for (const o of c.ops) {
        const d = 1 - a;
        gl.bindFramebuffer(gl.FRAMEBUFFER, A[d].f); gl.viewport(0, 0, c.w, c.h); gl.useProgram(pm);
        gl.activeTexture(gl.TEXTURE0); gl.bindTexture(gl.TEXTURE_2D, A[a].t);
        const L = n => gl.getUniformLocation(pm, n);
        gl.uniform1i(L('uPrev'), 0); gl.uniform2f(L('uSize'), c.w, c.h); gl.uniform1i(L('uShape'), o.shape); gl.uniform1i(L('uMode'), o.mode);
        gl.uniform1f(L('uInvert'), o.invert); gl.uniform2f(L('uC'), o.cx, o.cy); gl.uniform2f(L('uHalf'), o.hw, o.hh);
        gl.uniform2f(L('uRot'), o.cos, o.sin); gl.uniform1f(L('uFeather'), o.feather); gl.uniform1f(L('uExpand'), o.expand);
        gl.uniform1f(L('uOpacity'), o.opacity); gl.uniform1f(L('uCorner'), o.corner); gl.uniform1f(L('uAn'), o.an);
        gl.uniform2f(L('uAnCs'), o.anCos, o.anSin); gl.uniform2f(L('uEn'), o.enCos, o.enSin); gl.uniform1i(L('uN'), o.nPts);
        if (o.shape === 6 && o.nPts > 0) gl.uniform2fv(L('uPts'), new Float32Array(o.pts));
        gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4);
        a = d;
      }
      // apply pass on a known RGBA picture (premultiplied grey-ish gradient)
      const pic = target(c.w, c.h, 'rgba');
      const px = new Uint8Array(c.w * c.h * 4);
      for (let i = 0; i < c.w * c.h; i++) { px[4 * i] = 200; px[4 * i + 1] = 100; px[4 * i + 2] = 50; px[4 * i + 3] = 255; }
      gl.bindTexture(gl.TEXTURE_2D, pic.t); gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA8, c.w, c.h, 0, gl.RGBA, gl.UNSIGNED_BYTE, px);
      const dst = target(c.w, c.h, 'rgba');
      gl.bindFramebuffer(gl.FRAMEBUFFER, dst.f); gl.viewport(0, 0, c.w, c.h); gl.useProgram(pa);
      gl.activeTexture(gl.TEXTURE0); gl.bindTexture(gl.TEXTURE_2D, pic.t);
      gl.activeTexture(gl.TEXTURE1); gl.bindTexture(gl.TEXTURE_2D, A[a].t);
      gl.uniform1i(gl.getUniformLocation(pa, 'uTex'), 0); gl.uniform1i(gl.getUniformLocation(pa, 'uMask'), 1);
      gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4);
      const buf = new Uint8Array(c.w * c.h * 4);
      gl.readPixels(0, 0, c.w, c.h, gl.RGBA, gl.UNSIGNED_BYTE, buf);
      // buffer rows are bottom-up: image row y (from the top) = buf row h-1-y. alpha channel = 255 * coverage
      let maxErr = 0, bad = 0, sum = 0;
      const sample = [];
      for (let y = 0; y < c.h; y++) for (let x = 0; x < c.w; x++) {
        const g = buf[((c.h - 1 - y) * c.w + x) * 4 + 3] / 255;
        const r = buf[((c.h - 1 - y) * c.w + x) * 4] / 200;  // red channel = 200 * coverage
        const cpu = c.grid[y * c.w + x];
        const e = Math.abs(g - cpu);
        if (e > maxErr) maxErr = e;
        if (e > 0.02) bad++;
        sum += cpu;
        if (Math.abs(r - g) > 0.02) bad++;
      }
      out.push({ name: c.name, maxErr, bad, mean: sum / (c.w * c.h) });
    }
    return { out };
  }, { cfgs, vs: vertex(), kMask: extract('kMask'), kApply: extract('kMaskApply') });
  await browser.close();
  if (res.error) { console.log('ERROR', res.error); process.exit(2); }
  let fail = false;
  for (const r of res.out) {
    console.log(`${r.name.padEnd(22)} maxErr=${r.maxErr.toFixed(4)} badPixels=${r.bad} meanCoverage=${r.mean.toFixed(3)}`);
    if (r.bad > 0 || r.maxErr > 0.03) fail = true;
  }
  console.log(fail ? 'GPU CHECK FAILED' : 'GPU CHECK OK');
  process.exit(fail ? 1 : 0);
})().catch(e => { console.log('EXCEPTION', e.message); process.exit(3); });
