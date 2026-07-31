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
