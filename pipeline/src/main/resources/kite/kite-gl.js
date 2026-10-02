(function (global) {
  'use strict';

  var DEF_SMOOTH = '#70c8e8';
  var DEF_URGENT = '#b84038';
  var AXIS_KEYS = ['axis.PROG.normalized', 'axis.SPON.normalized', 'axis.GUID.normalized'];
  var AXIS_HUMAN = ['进度', '自发性', '引导性'];
  var FRAME_WINDOW = 200;

  function hex2rgb(h, fallback) {
    var m = /^#([0-9a-fA-F]{6})$/.exec(String(h || ''));
    if (!m) { m = /^#([0-9a-fA-F]{6})$/.exec(fallback); }
    var v = parseInt(m[1], 16);
    return [((v >> 16) & 255) / 255, ((v >> 8) & 255) / 255, (v & 255) / 255];
  }

  function sub(a, b) { return [a[0] - b[0], a[1] - b[1], a[2] - b[2]]; }
  function cross(a, b) {
    return [a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]];
  }
  function dot(a, b) { return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]; }
  function norm(a) { var l = Math.sqrt(dot(a, a)) || 1e-12; return [a[0] / l, a[1] / l, a[2] / l]; }

  function orbitBasis(az, el) {
    var ca = Math.cos(az), sa = Math.sin(az), ce = Math.cos(el), se = Math.sin(el);
    var fwd = [sa * ce, -se, ca * ce];
    var right = norm(cross(fwd, [0, 1, 0]));
    var up = cross(right, fwd);
    return { fwd: fwd, right: right, up: up };
  }

  function project(p, cam, view) {
    var d = sub(p, view.center);
    var x = dot(d, view.basis.right);
    var y = dot(d, view.basis.up);
    var z = dot(d, view.basis.fwd);
    var depth = cam.dist - z;
    if (depth < 1e-4) { depth = 1e-4; }
    var s = (cam.focal * view.px) / depth;
    return [view.cx + x * s, view.cy - y * s, depth];
  }

  function pointsFromData(data) {
    var out = [], skipped = 0;
    var pts = (data && data.points) || [];
    for (var i = 0; i < pts.length; i++) {
      var obs = pts[i].obs || [], v = [null, null, null], dens = null;
      for (var j = 0; j < obs.length; j++) {
        var k = obs[j].k, val = obs[j].v;
        var ix = AXIS_KEYS.indexOf(k);
        if (ix >= 0) { v[ix] = (val === null || val === undefined) ? null : Number(val); }
        else if (k === 'v.density_per_min') { dens = Number(val); }
      }
      if (v[0] === null || v[1] === null || v[2] === null) { skipped++; continue; }
      out.push({ t: Number(pts[i].t), i: Number(pts[i].i), v: v, dens: dens });
    }
    return { points: out, skipped: skipped, total: pts.length };
  }

  function segmentColors(points, urgent) {
    var segs = [], maxRate = 1e-9, k;
    for (k = 1; k < points.length; k++) {
      var d1 = sub(points[k].v, points[k - 1].v);
      var l1 = Math.sqrt(dot(d1, d1));
      var rate = 0;
      if (k >= 2) {
        var d0 = sub(points[k - 1].v, points[k - 2].v);
        var l0 = Math.sqrt(dot(d0, d0));
        if (l0 > 1e-9 && l1 > 1e-9) {
          var cosA = dot(d0, d1) / (l0 * l1);
          cosA = Math.max(-1, Math.min(1, cosA));
          rate = Math.acos(cosA);
        }
      }
      if (rate > maxRate) { maxRate = rate; }
      segs.push({ a: points[k - 1].v, b: points[k].v, rate: rate, i: k });
    }
    for (k = 0; k < segs.length; k++) { segs[k].u = maxRate > 1e-9 ? segs[k].rate / maxRate : 0; }
    return { segs: segs, maxTurnRate: maxRate, urgentCut: urgent };
  }

  function planesFromModel(model) {
    var out = [], skipped = 0, raw = (model && model.planes) || [];
    for (var i = 0; i < raw.length; i++) {
      var pl = raw[i], poly = pl && (pl.corners || pl.poly || pl.points || pl.quad);
      if (poly && poly.length >= 3) {
        var ok = true, p = [];
        for (var j = 0; j < poly.length; j++) {
          var q = poly[j];
          if (!q || q.length < 3 || !isFinite(q[0]) || !isFinite(q[1]) || !isFinite(q[2])) { ok = false; break; }
          p.push([Number(q[0]), Number(q[1]), Number(q[2])]);
        }
        if (ok) {
          out.push({
            poly: p,
            stage: (pl.stage !== undefined ? pl.stage : i),
            id: (pl.id || ("stage" + (i + 1))),
            label: (pl.label || ""),
            opacity: (typeof pl.opacity === "number" ? pl.opacity : 0.16)
          });
          continue;
        }
      }
      skipped++;
    }
    return { planes: out, skipped: skipped, total: raw.length };
  }

  function pointsFromModel(model) {
    var out = [], skipped = 0, raw = (model && model.points) || [];
    for (var i = 0; i < raw.length; i++) {
      var p = raw[i] && raw[i].p;
      if (!p || p.length < 3 || !isFinite(p[0]) || !isFinite(p[1]) || !isFinite(p[2])) { skipped++; continue; }
      out.push({ i: (raw[i].i !== undefined ? raw[i].i : i), t: null, v: [Number(p[0]), Number(p[1]), Number(p[2])] });
    }
    return { points: out, skipped: skipped, total: raw.length };
  }

  function segmentsFromModel(model) {
    var raw = (model && model.segments) || [], segs = [], skipped = 0, maxRate = 1e-9;
    for (var k = 0; k < raw.length; k++) {
      var s = raw[k], a = s && s.a, b = s && s.b;
      if (!a || !b || a.length < 3 || b.length < 3) { skipped++; continue; }
      var st = String(s.state === undefined ? '' : s.state).toLowerCase();
      var u = null;
      if (st === 'smooth' || st === 'continuous' || st === 'steady') { u = 0; }
      else if (st === 'urgent' || st === 'sharp' || st === 'abrupt') { u = 1; }
      else if (st === 'undecidable' || st === 'unknown' || st === '') { u = null; }
      segs.push({ a: a, b: b, state: st, u: u, w: s.w, i: k });
    }
    var noState = segs.length > 0;
    for (var z = 0; z < segs.length; z++) { if (segs[z].u !== null) { noState = false; break; } }
    if (noState) {
      for (var m = 1; m < segs.length; m++) {
        var d1 = sub(segs[m].b, segs[m].a), l1 = Math.sqrt(dot(d1, d1));
        var d0 = sub(segs[m - 1].b, segs[m - 1].a), l0 = Math.sqrt(dot(d0, d0));
        var rate = 0;
        if (l0 > 1e-9 && l1 > 1e-9) {
          var c = Math.max(-1, Math.min(1, dot(d0, d1) / (l0 * l1)));
          rate = Math.acos(c);
        }
        segs[m].rate = rate;
        if (rate > maxRate) { maxRate = rate; }
      }
      for (var q2 = 0; q2 < segs.length; q2++) { segs[q2].u = maxRate > 1e-9 ? (segs[q2].rate || 0) / maxRate : 0; }
    }
    return { segs: segs, skipped: skipped, total: raw.length, usedRateFallback: noState };
  }

  function mixer(a, b, u) { return [a[0] + (b[0] - a[0]) * u, a[1] + (b[1] - a[1]) * u, a[2] + (b[2] - a[2]) * u]; }

  var VS = [
    'attribute vec3 aPos;',
    'attribute vec3 aCol;',
    'attribute float aAlpha;',
    'uniform mat4 uMVP;',
    'uniform float uPointSize;',
    'varying vec3 vCol;',
    'varying float vAlpha;',
    'void main(){',
    '  vCol = aCol; vAlpha = aAlpha;',
    '  gl_Position = uMVP * vec4(aPos, 1.0);',
    '  gl_PointSize = uPointSize;',
    '}'
  ].join('\n');

  var FS = [
    'precision mediump float;',
    'varying vec3 vCol;',
    'varying float vAlpha;',
    'void main(){',
    '  gl_FragColor = vec4(vCol, vAlpha);',
    '}'
  ].join('\n');

  function create(canvas, model, data, opts) {
    opts = opts || {};
    var report = { status: 'unknown', notes: [] };
    function note(s) { if (report.notes.length < 12) { report.notes.push(s); } }

    var gl = null, isGL2 = false;
    try {
      gl = canvas.getContext('webgl2', { antialias: true, alpha: false });
      if (gl) { isGL2 = true; }
      if (!gl) { gl = canvas.getContext('webgl', { antialias: true, alpha: false }); }
    } catch (e) { gl = null; }
    if (!gl) {
      report.status = 'no-context';
      note('getContext("webgl2") 与 "webgl" 均返回 null');
      return { report: report, dispose: function () {} };
    }

    function compile(type, src) {
      var sh = gl.createShader(type);
      gl.shaderSource(sh, src);
      gl.compileShader(sh);
      if (!gl.getShaderParameter(sh, gl.COMPILE_STATUS)) {
        note('着色器编译失败：' + String(gl.getShaderInfoLog(sh)).slice(0, 200));
        return null;
      }
      return sh;
    }
    var vs = compile(gl.VERTEX_SHADER, VS), fs = compile(gl.FRAGMENT_SHADER, FS);
    if (!vs || !fs) { report.status = 'shader-fail'; return { report: report, dispose: function () {} }; }
    var prog = gl.createProgram();
    gl.attachShader(prog, vs); gl.attachShader(prog, fs); gl.linkProgram(prog);
    if (!gl.getProgramParameter(prog, gl.LINK_STATUS)) {
      report.status = 'shader-fail';
      note('程序链接失败：' + String(gl.getProgramInfoLog(prog)).slice(0, 200));
      return { report: report, dispose: function () {} };
    }
    gl.useProgram(prog);

    var useModel = !!(model && model.points && model.points.length && model.segments && model.segments.length);
    var dp = useModel ? pointsFromModel(model) : pointsFromData(data);
    note("几何真值源 = " + (useModel ? "scene-model（生成器自带 points/segments）" : "data 载荷反推（模型缺几何，已降级并声明）"));
    var colors = useModel ? segmentsFromModel(model) : segmentColors(dp.points, opts.urgentCut === undefined ? 0.5 : opts.urgentCut);
    if (useModel && colors.usedRateFallback) { note("模型未给 state ⇒ 颜色回退为按转角连续着色（已声明）"); }
    if (useModel && colors.skipped) { note("线段跳过 " + colors.skipped + " 条（几何不全，不补造）"); }
    var pl = planesFromModel(model);
    if (pl.skipped > 0) { note('阶段平面：模型给出 ' + pl.total + ' 个，可解析 ' + pl.planes.length + ' 个，跳过 ' + pl.skipped + ' 个（不造几何）'); }

    var nSeg = colors.segs.length, nPt = dp.points.length;
    var segData = new Float32Array(nSeg * 2 * 7);
    var ptData = new Float32Array(nPt * 7);
    var triCount = 0, triData = null;
    var k, o = 0;
    for (k = 0; k < nSeg; k++) {
      var s = colors.segs[k], col, al;
      if (s.u === null || s.u === undefined) { col = axisCol; al = 0.35; }
      else { col = mixer(smooth, urgent, s.u); al = 0.55 + 0.45 * Math.min(1, s.u + 0.25); }
      var A = toWorld(s.a), B = toWorld(s.b);
      o = put(segData, o, A, col, al); o = put(segData, o, B, col, al);
    }
    o = 0;
    for (k = 0; k < nPt; k++) {
      var P = toWorld(dp.points[k].v);
      o = put(ptData, o, P, [1, 1, 1], 0.9);
    }
    if (pl.planes.length) {
      triCount = pl.planes.length * 2;
      triData = new Float32Array(triCount * 3 * 7);
      o = 0;
      for (k = 0; k < pl.planes.length; k++) {
        var q = pl.planes[k].poly, op = (typeof pl.planes[k].opacity === "number" ? pl.planes[k].opacity : 0.16), w = [];
        for (var z2 = 0; z2 < q.length; z2++) { w.push(toWorld(q[z2])); }
        o = put(triData, o, w[0], gridCol, op); o = put(triData, o, w[1], gridCol, op); o = put(triData, o, w[2], gridCol, op);
        o = put(triData, o, w[0], gridCol, op); o = put(triData, o, w[2], gridCol, op); o = put(triData, o, w[3 % w.length], gridCol, op);
      }
    }
    function put(arr, off, p, col, al) {
      arr[off] = p[0]; arr[off + 1] = p[1]; arr[off + 2] = p[2];
      arr[off + 3] = col[0]; arr[off + 4] = col[1]; arr[off + 5] = col[2]; arr[off + 6] = al;
      return off + 7;
    }
    var segBuf = buf(segData), ptBuf = buf(ptData), triBuf = triData ? buf(triData) : null;
    function buf(arr) { var b = gl.createBuffer(); gl.bindBuffer(gl.ARRAY_BUFFER, b); gl.bufferData(gl.ARRAY_BUFFER, arr, gl.STATIC_DRAW); return b; }

    var axData = new Float32Array(3 * 2 * 7), ao = 0;
    var cube = [-0.5 * 2 * rad, 0, 0];
    var c0 = toWorld([0, 0, 0]), c1 = toWorld([1, 0, 0]), c2 = toWorld([0, 1, 0]), c3 = toWorld([0, 0, 1]);
    ao = put(axData, ao, c0, axisCol, 1); ao = put(axData, ao, c1, axisCol, 1);
    ao = put(axData, ao, c0, axisCol, 1); ao = put(axData, ao, c2, axisCol, 1);
    ao = put(axData, ao, c0, axisCol, 1); ao = put(axData, ao, c3, axisCol, 1);
    var axBuf = buf(axData);
    if (cube[0] === 1e9) { note('unreachable'); }

    var loc = {
      pos: gl.getAttribLocation(prog, 'aPos'),
      col: gl.getAttribLocation(prog, 'aCol'),
      alp: gl.getAttribLocation(prog, 'aAlpha'),
      mvp: gl.getUniformLocation(prog, 'uMVP'),
      psz: gl.getUniformLocation(prog, 'uPointSize')
    };
    function bind(b) {
      gl.bindBuffer(gl.ARRAY_BUFFER, b);
      gl.enableVertexAttribArray(loc.pos); gl.vertexAttribPointer(loc.pos, 3, gl.FLOAT, false, 28, 0);
      gl.enableVertexAttribArray(loc.col); gl.vertexAttribPointer(loc.col, 3, gl.FLOAT, false, 28, 12);
      gl.enableVertexAttribArray(loc.alp); gl.vertexAttribPointer(loc.alp, 1, gl.FLOAT, false, 28, 24);
    }

    function mvp(w, h) {
      var aspect = w / h, f = 1 / Math.tan((model && model.fov ? model.fov : 0.62) / 2);
      var view = orbitBasis(cam.az, cam.el);
      var m = new Float32Array(16);
      m[0] = view.right[0]; m[1] = view.up[0]; m[2] = -view.fwd[0]; m[3] = 0;
      m[4] = view.right[1]; m[5] = view.up[1]; m[6] = -view.fwd[1]; m[7] = 0;
      m[8] = view.right[2]; m[9] = view.up[2]; m[10] = -view.fwd[2]; m[11] = 0;
      var ctr = (model && model.center) || [0, 0, 0];
      m[12] = -(view.right[0] * ctr[0] + view.right[1] * ctr[1] + view.right[2] * ctr[2]);
      m[13] = -(view.up[0] * ctr[0] + view.up[1] * ctr[1] + view.up[2] * ctr[2]);
      m[14] = (view.fwd[0] * ctr[0] + view.fwd[1] * ctr[1] + view.fwd[2] * ctr[2]) + cam.dist;
      m[15] = 1;
      var p = new Float32Array(16);
      var NEAR = 0.05, FAR = 200.0;
      var B = -2.0 * NEAR * FAR / (FAR - NEAR);
      p[0] = f / aspect; p[5] = f; p[10] = 1.0 - B / FAR; p[11] = 1; p[14] = B; p[15] = 0;
      var out = new Float32Array(16);
      for (var i2 = 0; i2 < 4; i2++) {
        for (var j2 = 0; j2 < 4; j2++) {
          var sum = 0;
          for (var t2 = 0; t2 < 4; t2++) { sum += p[t2 * 4 + j2] * m[i2 * 4 + t2]; }
          out[i2 * 4 + j2] = sum;
        }
      }
      return { mat: out, basis: view };
    }

    var frames = [], last = 0, drawn = 0;
    function frameStats(now) {
      if (last > 0) {
        frames.push(now - last);
        if (frames.length > FRAME_WINDOW) { frames.shift(); }
      }
      last = now;
      var sum = 0, mx = 0;
      for (var i3 = 0; i3 < frames.length; i3++) { sum += frames[i3]; if (frames[i3] > mx) { mx = frames[i3]; } }
      return { n: frames.length, avg: frames.length ? sum / frames.length : 0, max: mx };
    }

    var overlay = opts.overlay || null, sel = -1;
    function draw(now) {
      var w = canvas.width, h = canvas.height;
      gl.viewport(0, 0, w, h);
      gl.clearColor(bg[0], bg[1], bg[2], 1);
      gl.clear(gl.COLOR_BUFFER_BIT | gl.DEPTH_BUFFER_BIT);
      gl.enable(gl.DEPTH_TEST);
      gl.enable(gl.BLEND);
      gl.blendFunc(gl.SRC_ALPHA, gl.ONE_MINUS_SRC_ALPHA);
      var mv = mvp(w, h);
      gl.uniformMatrix4fv(loc.mvp, false, mv.mat);
      if (triBuf) { gl.uniform1f(loc.psz, 1.0); gl.depthMask(false); bind(triBuf); gl.drawArrays(gl.TRIANGLES, 0, triCount * 3); gl.depthMask(true); }
      gl.uniform1f(loc.psz, 1.0); bind(axBuf); gl.drawArrays(gl.LINES, 0, 6);
      gl.uniform1f(loc.psz, 1.0); bind(segBuf); gl.drawArrays(gl.LINES, 0, nSeg * 2);
      gl.uniform1f(loc.psz, 5.0); bind(ptBuf); gl.drawArrays(gl.POINTS, 0, nPt);
      drawn++;
      var fs = frameStats(now || 0);
      report.primitives = { points: nPt, segments: nSeg, planes: pl.planes.length * 2,
                            vertices: nPt + nSeg * 2 + triCount * 3 + 6, drawCalls: triBuf ? 4 : 3 };
      report.frames = fs;
      report.status = 'ok';
      if (overlay) { overlay(mv.basis, cam, w, h); }
    }

    return {
      report: report,
      draw: draw,
      camera: cam,
      defaultCamera: def,
      points: dp.points,
      project: function (p, w, h) {
        var mv = mvp(w, h);
        return project(p, cam, { basis: mv.basis, px: (h / (2 * Math.tan((model && model.fov ? model.fov : 0.62) / 2))) / cam.focal, cx: w / 2, cy: h / 2, center: [0, 0, 0] });
      },
      select: function (i) { sel = i; },
      dispose: function () {
        [segBuf, ptBuf, triBuf, axBuf].forEach(function (b) { if (b) { gl.deleteBuffer(b); } });
        gl.deleteProgram(prog); gl.deleteShader(vs); gl.deleteShader(fs);
      },
      stats: function () { return { frames: report.frames, primitives: report.primitives, drawn: drawn }; }
    };
  }

  function attach(canvas, model, data, ui) {
    ui = ui || {};
    var r = create(canvas, model, data, {});
    var cam = r.camera || null;
    if (r.report.status !== 'ok') {
      var host = ui.host || canvas;
      host.setAttribute('data-gl-status', r.report.status);
      host.setAttribute('data-gl-notes', r.report.notes.join(' | '));
      var st = ui.staticSnapshot;
      if (st) { st.removeAttribute('hidden'); }
      return { report: r.report, dispose: r.dispose || function () {} };
    }
    var drag = false, pan = false, lx = 0, ly = 0;
    canvas.addEventListener('pointerdown', function (e) {
      drag = true; pan = (e.button === 2 || e.shiftKey); lx = e.clientX; ly = e.clientY;
      if (canvas.setPointerCapture) { try { canvas.setPointerCapture(e.pointerId); } catch (err) {  } }
    });
    canvas.addEventListener('pointermove', function (e) {
      if (!drag || !cam) { return; }
      var dx = e.clientX - lx, dy = e.clientY - ly; lx = e.clientX; ly = e.clientY;
      if (pan) { cam.panX = (cam.panX || 0) + dx; cam.panY = (cam.panY || 0) + dy; }
      else { cam.az += dx * 0.01; cam.el = Math.max(-1.45, Math.min(1.45, cam.el + dy * 0.01)); }
      requestFrame();
    });
    canvas.addEventListener('pointerup', function () { drag = false; });
    canvas.addEventListener('pointercancel', function () { drag = false; });
    canvas.addEventListener('wheel', function (e) {
      if (!cam) { return; }
      e.preventDefault();
      cam.dist = Math.max(0.6, Math.min(8, cam.dist * Math.exp(e.deltaY * 0.0015)));
      requestFrame();
    }, { passive: false });
    canvas.addEventListener('dblclick', function () { reset(); });
    function reset() {
      if (!cam) { return; }
      cam.az = r.defaultCamera.az; cam.el = r.defaultCamera.el; cam.dist = r.defaultCamera.dist;
      cam.panX = 0; cam.panY = 0; requestFrame();
    }
    if (ui.reset) { ui.reset.addEventListener('click', reset); }
    if (ui.viewA) { ui.viewA.addEventListener('click', function () { if (cam) { cam.az = r.defaultCamera.az; cam.el = r.defaultCamera.el; requestFrame(); } }); }
    if (ui.viewB) { ui.viewB.addEventListener('click', function () { if (cam) { cam.az = 0; cam.el = 1.30; requestFrame(); } }); }
    var pending = false;
    function requestFrame() {
      if (pending) { return; }
      pending = true;
      (global.requestAnimationFrame || function (f) { return setTimeout(function () { f(Date.now()); }, 16); })(function (ts) {
        pending = false;
        r.draw(ts);
        writeSelfCheck();
      });
    }
    function writeSelfCheck() {
      var host = ui.host || canvas;
      var st = r.stats();
      host.setAttribute('data-gl-status', r.report.status);
      host.setAttribute('data-gl-primitives',
        'points=' + st.primitives.points + ';segments=' + st.primitives.segments +
        ';triangles=' + st.primitives.planes + ';vertices=' + st.primitives.vertices +
        ';drawCalls=' + st.primitives.drawCalls);
      host.setAttribute('data-gl-frames', 'n=' + st.frames.n + ';avg=' + st.frames.avg.toFixed(2) +
        ';max=' + st.frames.max.toFixed(2));
      if (r.report.notes.length) { host.setAttribute('data-gl-notes', r.report.notes.join(' | ')); }
    }
    return { report: r.report, draw: r.draw, requestFrame: requestFrame, dispose: r.dispose, stats: r.stats };
  }

  var api = {
    pointsFromData: pointsFromData,
    segmentColors: segmentColors,
    planesFromModel: planesFromModel,
    pointsFromModel: pointsFromModel,
    segmentsFromModel: segmentsFromModel,
    orbitBasis: orbitBasis,
    project: project,
    create: create,
    attach: attach,
    AXIS_KEYS: AXIS_KEYS,
    AXIS_HUMAN: AXIS_HUMAN
  };
  if (typeof module !== 'undefined' && module.exports) { module.exports = api; }
  global.KiteGL = api;
})(typeof window !== 'undefined' ? window : this);
