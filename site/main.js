/* Reveal on scroll, the HUD tier demo, and the plugin rail. */

(function () {
  'use strict';

  var reduced = window.matchMedia('(prefers-reduced-motion: reduce)').matches;

  /* ── nav ─────────────────────────────────────────────── */

  var nav = document.getElementById('nav');
  if (nav) {
    var onScroll = function () { nav.classList.toggle('is-stuck', window.scrollY > 12); };
    onScroll();
    window.addEventListener('scroll', onScroll, { passive: true });
  }

  /* ── reveal ──────────────────────────────────────────── */

  var targets = document.querySelectorAll('.reveal');
  if (!('IntersectionObserver' in window) || reduced) {
    Array.prototype.forEach.call(targets, function (el) { el.classList.add('is-in'); });
  } else {
    var io = new IntersectionObserver(function (entries) {
      entries.forEach(function (entry) {
        if (!entry.isIntersecting) return;
        entry.target.classList.add('is-in');
        io.unobserve(entry.target);
      });
    }, { rootMargin: '0px 0px -12% 0px', threshold: 0.08 });
    Array.prototype.forEach.call(targets, function (el) { io.observe(el); });
  }

  /* ── the hero field ──────────────────────────────────────
     The bus, drawn: one hub, plugins around it, and messages
     travelling inward. It follows the pointer, lights the nodes
     you come near, and fires a message from wherever you click. */

  var field = document.getElementById('field');
  if (field && field.getContext) (function () {
    var hero = document.querySelector('.hero');
    var ctx = field.getContext('2d');
    var W = 0, H = 0, S = 0, cx = 0, cy = 0;
    var nodes = [], pulses = [], ptr = { x: -1e5, y: -1e5, on: false };
    var raf = 0, last = 0, clock = 0, nextEmit = 900, visible = true;

    var RINGS = [
      { n: 5, r: 0.155, amp: 0.013, size: 3.3, drift:  0.000040 },
      { n: 7, r: 0.310, amp: 0.019, size: 2.9, drift: -0.000028 },
      { n: 4, r: 0.442, amp: 0.024, size: 2.5, drift:  0.000018 }
    ];

    function build() {
      nodes = [{ ring: 0, a: 0, r: 0, amp: 0, drift: 0, spd: 0.0009, ph: 0, size: 5.4, flash: 0 }];
      RINGS.forEach(function (cfg, i) {
        for (var k = 0; k < cfg.n; k++) {
          nodes.push({
            ring: i + 1,
            a: (k / cfg.n) * Math.PI * 2 + i * 0.83,
            r: cfg.r, amp: cfg.amp, drift: cfg.drift,
            spd: 0.00022 + i * 0.00009,
            ph: k * 1.7 + i * 2.1,
            size: cfg.size, flash: 0
          });
        }
      });
    }

    function resize() {
      var rect = field.getBoundingClientRect();
      var dpr = Math.min(2, window.devicePixelRatio || 1);
      W = Math.max(1, Math.round(rect.width));
      H = Math.max(1, Math.round(rect.height));
      field.width = Math.round(W * dpr);
      field.height = Math.round(H * dpr);
      ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
      S = Math.min(W, H);
      cx = W / 2;
      cy = H / 2;
    }

    function place(t) {
      for (var i = 0; i < nodes.length; i++) {
        var nd = nodes[i];
        var a = nd.a + t * nd.drift;
        var r = (nd.r + Math.sin(t * nd.spd + nd.ph) * nd.amp) * S;
        var x = cx + Math.cos(a) * r;
        var y = cy + Math.sin(a) * r;
        if (ptr.on) {                       // the pointer bends the field a little
          var dx = ptr.x - x, dy = ptr.y - y;
          var d = Math.hypot(dx, dy);
          var R = S * 0.22;
          if (d < R && d > 0.01) {
            var pull = (1 - d / R);
            x += (dx / d) * pull * 9;
            y += (dy / d) * pull * 9;
            nd.flash = Math.max(nd.flash, pull * 0.85);
          }
        }
        nd._x = x;
        nd._y = y;
      }
    }

    function emit(from) {
      var path = [from], cur = nodes[from], guard = 0;
      while (cur.ring > 0 && guard++ < 5) {
        var best = -1, bd = Infinity;
        for (var i = 0; i < nodes.length; i++) {
          if (nodes[i].ring !== cur.ring - 1) continue;
          var d = Math.hypot(nodes[i]._x - cur._x, nodes[i]._y - cur._y);
          if (d < bd) { bd = d; best = i; }
        }
        if (best < 0) break;
        path.push(best);
        cur = nodes[best];
      }
      if (path.length > 1) pulses.push({ path: path, seg: 0, p: 0 });
    }

    function step(dt) {
      for (var i = nodes.length; i--;) nodes[i].flash *= Math.pow(0.9975, dt);
      for (var j = pulses.length; j--;) {
        var pu = pulses[j];
        pu.p += dt / 520;
        while (pu.p >= 1) {
          pu.p -= 1;
          pu.seg++;
          var landed = nodes[pu.path[pu.seg]];
          if (landed) landed.flash = 1;
          if (pu.seg >= pu.path.length - 1) { pulses.splice(j, 1); break; }
        }
      }
      nextEmit -= dt;
      if (nextEmit <= 0 && pulses.length < 4) {
        var outer = [];
        for (var k = 0; k < nodes.length; k++) if (nodes[k].ring >= 2) outer.push(k);
        if (outer.length) emit(outer[(Math.random() * outer.length) | 0]);
        nextEmit = 1200 + Math.random() * 1400;
      }
    }

    function draw() {
      ctx.clearRect(0, 0, W, H);

      var glow = ctx.createRadialGradient(cx, cy, 0, cx, cy, S * 0.46);
      glow.addColorStop(0, 'rgba(60,240,123,.13)');
      glow.addColorStop(0.5, 'rgba(60,240,123,.028)');
      glow.addColorStop(1, 'rgba(60,240,123,0)');
      ctx.fillStyle = glow;
      ctx.fillRect(0, 0, W, H);

      ctx.lineWidth = 1;
      [0.168, 0.272, 0.375, 0.472].forEach(function (r, i) {
        ctx.beginPath();
        ctx.arc(cx, cy, S * r, 0, Math.PI * 2);
        if (i === 1) { ctx.setLineDash([3, 9]); ctx.lineDashOffset = -clock * 0.006; }
        else ctx.setLineDash([]);
        ctx.strokeStyle = 'rgba(60,240,123,' + (i === 1 ? .12 : .085) + ')';
        ctx.stroke();
      });
      ctx.setLineDash([]);

      var LINK = S * 0.265;
      for (var i = 0; i < nodes.length; i++) {
        for (var j = i + 1; j < nodes.length; j++) {
          var a = nodes[i], b = nodes[j];
          var d = Math.hypot(a._x - b._x, a._y - b._y);
          if (d > LINK) continue;
          var lit = Math.max(a.flash, b.flash);
          ctx.strokeStyle = 'rgba(60,240,123,' + ((1 - d / LINK) * 0.24 + lit * 0.26).toFixed(3) + ')';
          ctx.beginPath();
          ctx.moveTo(a._x, a._y);
          ctx.lineTo(b._x, b._y);
          ctx.stroke();
        }
      }

      if (ptr.on) {                          // the pointer is a node too
        var R = S * 0.2;
        for (var m = 0; m < nodes.length; m++) {
          var nd = nodes[m];
          var dd = Math.hypot(nd._x - ptr.x, nd._y - ptr.y);
          if (dd > R) continue;
          ctx.strokeStyle = 'rgba(60,240,123,' + ((1 - dd / R) * 0.3).toFixed(3) + ')';
          ctx.beginPath();
          ctx.moveTo(ptr.x, ptr.y);
          ctx.lineTo(nd._x, nd._y);
          ctx.stroke();
        }
      }

      for (var p = 0; p < pulses.length; p++) {
        var pu = pulses[p];
        var from = nodes[pu.path[pu.seg]], to = nodes[pu.path[pu.seg + 1]];
        if (!from || !to) continue;
        var e = pu.p < .5 ? 2 * pu.p * pu.p : 1 - Math.pow(-2 * pu.p + 2, 2) / 2;
        var x = from._x + (to._x - from._x) * e;
        var y = from._y + (to._y - from._y) * e;
        var tx = from._x + (to._x - from._x) * Math.max(0, e - .28);
        var ty = from._y + (to._y - from._y) * Math.max(0, e - .28);
        var trail = ctx.createLinearGradient(tx, ty, x, y);
        trail.addColorStop(0, 'rgba(60,240,123,0)');
        trail.addColorStop(1, 'rgba(120,255,170,.85)');
        ctx.strokeStyle = trail;
        ctx.lineWidth = 1.6;
        ctx.beginPath();
        ctx.moveTo(tx, ty);
        ctx.lineTo(x, y);
        ctx.stroke();
        ctx.lineWidth = 1;
        ctx.fillStyle = 'rgba(180,255,205,.95)';
        ctx.shadowColor = 'rgba(60,240,123,.9)';
        ctx.shadowBlur = 12;
        ctx.beginPath();
        ctx.arc(x, y, 2.1, 0, Math.PI * 2);
        ctx.fill();
        ctx.shadowBlur = 0;
      }

      for (var n = 0; n < nodes.length; n++) {
        var q = nodes[n];
        var breathe = q.ring === 0 ? (0.55 + 0.45 * (0.5 + 0.5 * Math.sin(clock * 0.0011))) : 1;
        var rad = q.size * (1 + q.flash * 0.5);
        ctx.fillStyle = 'rgba(60,240,123,' + (0.52 + q.flash * 0.48) * breathe + ')';
        ctx.shadowColor = 'rgba(60,240,123,' + (0.5 + q.flash * 0.5) + ')';
        ctx.shadowBlur = 8 + q.flash * 16;
        ctx.beginPath();
        ctx.arc(q._x, q._y, rad, 0, Math.PI * 2);
        ctx.fill();
        ctx.shadowBlur = 0;
      }
    }

    function frame(now) {
      var dt = Math.min(48, now - last || 16);
      last = now;
      clock += dt;
      place(clock);
      step(dt);
      draw();
      raf = requestAnimationFrame(frame);
    }

    function start() {
      if (raf || reduced) return;
      last = performance.now();
      raf = requestAnimationFrame(frame);
    }
    function stop() { if (raf) { cancelAnimationFrame(raf); raf = 0; } }

    build();
    resize();
    place(0);
    draw();

    window.addEventListener('resize', function () { resize(); place(clock); draw(); });
    if ('ResizeObserver' in window) {
      new ResizeObserver(function () {
        var rect = field.getBoundingClientRect();
        if (Math.round(rect.width) === W && Math.round(rect.height) === H) return;
        resize(); place(clock); draw();
      }).observe(field);
    }

    if (!reduced && hero && window.matchMedia('(hover: hover)').matches) {
      hero.addEventListener('pointermove', function (ev) {
        var rect = field.getBoundingClientRect();
        ptr.x = ev.clientX - rect.left;
        ptr.y = ev.clientY - rect.top;
        ptr.on = true;
        var w = window.innerWidth, h = window.innerHeight;
        hero.style.setProperty('--px', ((ev.clientX / w - .5) * -26).toFixed(1) + 'px');
        hero.style.setProperty('--py', ((ev.clientY / h - .5) * -18).toFixed(1) + 'px');
      });
      hero.addEventListener('pointerleave', function () {
        ptr.on = false;
        ptr.x = ptr.y = -1e5;
        hero.style.setProperty('--px', '0px');
        hero.style.setProperty('--py', '0px');
      });
    }

    if (!reduced) {
      hero.addEventListener('click', function (ev) {
        if (ev.target.closest('a, button')) return;
        var rect = field.getBoundingClientRect();
        var x = ev.clientX - rect.left, y = ev.clientY - rect.top;
        var best = -1, bd = Infinity;
        for (var i = 0; i < nodes.length; i++) {
          if (!nodes[i].ring) continue;
          var d = Math.hypot(nodes[i]._x - x, nodes[i]._y - y);
          if (d < bd) { bd = d; best = i; }
        }
        if (best >= 0) { nodes[best].flash = 1; emit(best); }
      });
    }

    if ('IntersectionObserver' in window) {
      new IntersectionObserver(function (entries) {
        visible = entries[0].isIntersecting;
        if (visible) start(); else stop();
      }, { threshold: 0.02 }).observe(field);
    } else start();

    document.addEventListener('visibilitychange', function () {
      if (document.hidden) stop(); else if (visible) start();
    });
  })();

  /* ── the HUD tier demo ───────────────────────────────────
     Durations are the renderer's own HudMotion tokens:
     180 ms in place, 280 ms arriving or changing shape,
     240 ms leaving. Nothing here is faster than the glasses.  */

  var hud = document.getElementById('hud');
  if (hud) (function () {
    var list    = document.getElementById('tierList');
    var motion  = document.getElementById('hudMotion');
    var toggle  = document.getElementById('hudToggle');
    var lyric   = document.getElementById('hudLyric');
    var foot    = document.getElementById('hudNoticeFoot');
    var chips   = document.querySelectorAll('#hudNoticeRow .hud__chip');

    var LYRICS = [
      'Everything in its right place',
      'There are two colours in my head',
      'What was that you tried to say?'
    ];

    var timers = [];
    var playing = !reduced;
    var index = 0;
    var lyricAt = 0;

    function clear() { timers.forEach(clearTimeout); timers = []; }
    function at(ms, fn) { timers.push(setTimeout(fn, ms)); }

    function say(text) { if (motion) motion.textContent = text; }
    function scene(name, step) {
      hud.dataset.scene = name;
      hud.dataset.step = step || 0;
      if (!list) return;
      Array.prototype.forEach.call(list.children, function (li) {
        li.classList.toggle('is-live', li.dataset.scene === name);
      });
    }
    function select(i) {
      Array.prototype.forEach.call(chips, function (chip, n) {
        chip.classList.toggle('sel', n === i);
      });
    }
    function resetNotice() {
      select(0);
      if (foot) foot.textContent = 'Scroll to choose · Back to dismiss';
    }
    function refreshLyric() {
      hud.classList.add('is-refreshing');
      at(180, function () {
        lyricAt = (lyricAt + 1) % LYRICS.length;
        if (lyric) lyric.textContent = LYRICS[lyricAt];
        hud.classList.remove('is-refreshing');
      });
    }

    /* each scene returns how long it holds the stage */
    var SCENES = [
      { name: 'ambient', run: function () {
          scene('ambient');
          say('in place · 180 ms · linear');
          at(1900, refreshLyric);
          at(3600, refreshLyric);
          return 5200;
        } },
      { name: 'pin', run: function () {
          scene('pin');
          say('enter · 280 ms · fast-out-slow-in');
          return 3600;
        } },
      { name: 'activity', run: function () {
          scene('activity', 0);
          say('chip · the ambient form');
          at(1900, function () {
            scene('activity', 1);
            say('flare · morph in place · 280 ms');
          });
          at(5100, function () {
            scene('activity', 0);
            say('collapse · back to the chip · 240 ms');
          });
          return 6600;
        } },
      { name: 'notice', run: function () {
          resetNotice();
          scene('notice', 0);
          say('band arriving · 280 ms');
          at(1500, function () { select(1); say('scroll moves the selection · 180 ms'); });
          at(2500, function () { select(0); });
          at(3600, function () {
            scene('notice', 2);
            if (foot) foot.textContent = 'Reply · Back to dismiss';
            say('answered — once, and only once');
          });
          at(5600, function () { scene('notice', 3); say('exit · 240 ms · fast-out-linear-in'); });
          return 6600;
        } },
      { name: 'surface', run: function () {
          scene('surface');
          say('the wearer opened this one');
          return 4600;
        } }
    ];

    function play(i) {
      clear();
      index = ((i % SCENES.length) + SCENES.length) % SCENES.length;
      var hold = SCENES[index].run();
      if (playing) at(hold, function () { play(index + 1); });
    }

    function setPlaying(on) {
      playing = on;
      if (toggle) {
        toggle.textContent = on ? 'Pause' : 'Play';
        toggle.setAttribute('aria-pressed', String(on));
      }
      if (on) play(index); else clear();
    }

    if (toggle) toggle.addEventListener('click', function () { setPlaying(!playing); });

    if (list) {
      Array.prototype.forEach.call(list.children, function (li, i) {
        var button = li.querySelector('button');
        if (button) button.addEventListener('click', function () { play(i); });
      });
    }

    /* only animate while it is on screen */
    if ('IntersectionObserver' in window && !reduced) {
      var stageIo = new IntersectionObserver(function (entries) {
        entries.forEach(function (entry) {
          if (entry.isIntersecting) { if (playing) play(index); }
          else clear();
        });
      }, { threshold: 0.15 });
      stageIo.observe(hud);
    }

    if (reduced) { scene('notice', 0); resetNotice(); say('motion off — reduced-motion'); if (toggle) toggle.textContent = 'Play'; }
    else play(0);
  })();

  /* ── the plugin rail ─────────────────────────────────── */

  var rail = document.getElementById('rail');
  if (rail) (function () {
    var prev = document.getElementById('railPrev');
    var next = document.getElementById('railNext');
    function step() {
      var slide = rail.querySelector('.slide');
      return slide ? slide.getBoundingClientRect().width + 16 : 260;
    }
    if (prev) prev.addEventListener('click', function () { rail.scrollBy({ left: -step() * 2, behavior: reduced ? 'auto' : 'smooth' }); });
    if (next) next.addEventListener('click', function () { rail.scrollBy({ left:  step() * 2, behavior: reduced ? 'auto' : 'smooth' }); });
  })();

})();
