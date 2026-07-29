/*
 * Orbit spatial navigation.
 *
 * WebView's built-in D-pad handling walks the DOM in tab order, which on any
 * real two-dimensional page produces nonsense: pressing RIGHT jumps to a footer
 * link because that happens to be the next node. This replaces it with
 * geometric navigation — RIGHT moves to the thing that is actually to the right.
 *
 * Native side calls into window.__ORBIT__ and reads the JSON return value.
 */
(function () {
  'use strict';

  if (window.__ORBIT__ && window.__ORBIT__.version === 1) {
    window.__ORBIT__.rescan();
    return;
  }

  var FOCUS_CLASS = '__orbit-focus';
  var STYLE_ID = '__orbit-style';

  var FOCUSABLE = [
    'a[href]', 'button', 'select', 'textarea', 'summary', 'details',
    'input:not([type="hidden"])',
    'video', 'audio', 'area[href]',
    // Iframes are included so things like a reCAPTCHA checkbox become a focus
    // stop. Their contents are cross-origin and cannot be scripted from here,
    // so activating one hands over to the pointer instead.
    'iframe',
    '[tabindex]:not([tabindex="-1"])',
    '[onclick]', '[role="button"]', '[role="link"]', '[role="tab"]',
    '[role="menuitem"]', '[role="menuitemcheckbox"]', '[role="menuitemradio"]',
    '[role="option"]', '[role="checkbox"]', '[role="radio"]', '[role="switch"]',
    '[role="treeitem"]', '[contenteditable="true"]'
  ].join(',');

  var current = null;

  /* ---------------------------------------------------------------- styling */

  function injectStyle() {
    if (document.getElementById(STYLE_ID)) return;
    var css =
      '.' + FOCUS_CLASS + '{' +
      'outline:3px solid #6AA6FF !important;' +
      'outline-offset:2px !important;' +
      'box-shadow:0 0 0 5px rgba(106,166,255,.30),0 0 18px 2px rgba(106,166,255,.45) !important;' +
      'border-radius:6px !important;' +
      'scroll-margin:120px !important;' +
      '}';
    var s = document.createElement('style');
    s.id = STYLE_ID;
    s.textContent = css;
    (document.head || document.documentElement).appendChild(s);
  }

  function mark(el) {
    unmark();
    if (!el) return;
    current = el;
    try { el.classList.add(FOCUS_CLASS); } catch (e) {}
  }

  function unmark() {
    if (current) {
      try { current.classList.remove(FOCUS_CLASS); } catch (e) {}
    }
    current = null;
  }

  /* ------------------------------------------------------------- geometry */

  function vw() { return window.innerWidth || document.documentElement.clientWidth; }
  function vh() { return window.innerHeight || document.documentElement.clientHeight; }

  function rectOf(el) {
    var r = el.getBoundingClientRect();
    return {
      left: r.left, top: r.top, right: r.right, bottom: r.bottom,
      width: r.width, height: r.height,
      cx: r.left + r.width / 2, cy: r.top + r.height / 2
    };
  }

  function isVisible(el) {
    if (!el || el.disabled) return false;
    if (el.getAttribute && el.getAttribute('aria-hidden') === 'true') return false;
    var r = el.getBoundingClientRect();
    if (r.width < 4 || r.height < 4) return false;
    // Ignore anything more than one and a half screens away: it keeps the
    // candidate set small on long pages, which keeps key response instant.
    if (r.bottom < -vh() * 1.5 || r.top > vh() * 2.5) return false;
    if (r.right < -vw() * 1.5 || r.left > vw() * 2.5) return false;
    var st;
    try { st = window.getComputedStyle(el); } catch (e) { return false; }
    if (!st) return false;
    if (st.visibility === 'hidden' || st.display === 'none') return false;
    if (parseFloat(st.opacity || '1') < 0.08) return false;
    if (st.pointerEvents === 'none') return false;
    return true;
  }

  function candidates() {
    var out = [];
    var list;
    try { list = document.querySelectorAll(FOCUSABLE); } catch (e) { return out; }
    for (var i = 0; i < list.length; i++) {
      var el = list[i];
      if (el === current) continue;
      if (isVisible(el)) out.push(el);
    }
    return out;
  }

  function overlap(a1, a2, b1, b2) {
    return Math.max(0, Math.min(a2, b2) - Math.max(a1, b1));
  }

  /**
   * Lower is better. The perpendicular term is weighted far higher than the
   * in-line term so that pressing RIGHT prefers the next item in the same row
   * over something closer but two rows down.
   */
  function score(a, b, dir) {
    var horizontal = (dir === 'left' || dir === 'right');
    var primary, secondary, ok;

    if (dir === 'right')      { ok = b.cx > a.cx + 1 && b.right > a.right + 1; }
    else if (dir === 'left')  { ok = b.cx < a.cx - 1 && b.left  < a.left  - 1; }
    else if (dir === 'down')  { ok = b.cy > a.cy + 1 && b.bottom > a.bottom + 1; }
    else                      { ok = b.cy < a.cy - 1 && b.top   < a.top   - 1; }
    if (!ok) return Infinity;

    if (horizontal) {
      primary = Math.abs(b.cx - a.cx);
      var vOver = overlap(a.top, a.bottom, b.top, b.bottom);
      secondary = vOver > 0 ? 0 : Math.min(Math.abs(b.top - a.bottom), Math.abs(a.top - b.bottom));
    } else {
      primary = Math.abs(b.cy - a.cy);
      var hOver = overlap(a.left, a.right, b.left, b.right);
      secondary = hOver > 0 ? 0 : Math.min(Math.abs(b.left - a.right), Math.abs(a.left - b.right));
    }
    return primary + secondary * 6;
  }

  function best(dir) {
    if (!current) return null;
    var a = rectOf(current);
    var list = candidates();
    var bestEl = null, bestScore = Infinity;
    for (var i = 0; i < list.length; i++) {
      var s = score(a, rectOf(list[i]), dir);
      if (s < bestScore) { bestScore = s; bestEl = list[i]; }
    }
    return bestEl;
  }

  /* ------------------------------------------------------------- scrolling */

  function scrollableAncestor(el, dir) {
    var node = el;
    while (node && node !== document.body && node !== document.documentElement) {
      var st;
      try { st = window.getComputedStyle(node); } catch (e) { st = null; }
      if (st) {
        var oy = st.overflowY, ox = st.overflowX;
        var canY = (oy === 'auto' || oy === 'scroll') && node.scrollHeight > node.clientHeight + 4;
        var canX = (ox === 'auto' || ox === 'scroll') && node.scrollWidth > node.clientWidth + 4;
        if (dir === 'down' && canY && node.scrollTop + node.clientHeight < node.scrollHeight - 2) return node;
        if (dir === 'up' && canY && node.scrollTop > 2) return node;
        if (dir === 'right' && canX && node.scrollLeft + node.clientWidth < node.scrollWidth - 2) return node;
        if (dir === 'left' && canX && node.scrollLeft > 2) return node;
      }
      node = node.parentElement;
    }
    return null;
  }

  function pageCanScroll(dir) {
    var doc = document.scrollingElement || document.documentElement;
    if (dir === 'down') return doc.scrollTop + vh() < doc.scrollHeight - 2;
    if (dir === 'up') return doc.scrollTop > 2;
    if (dir === 'right') return doc.scrollLeft + vw() < doc.scrollWidth - 2;
    return doc.scrollLeft > 2;
  }

  function doScroll(dir, smooth) {
    var stepY = Math.round(vh() * 0.75);
    var stepX = Math.round(vw() * 0.75);
    var dx = dir === 'right' ? stepX : dir === 'left' ? -stepX : 0;
    var dy = dir === 'down' ? stepY : dir === 'up' ? -stepY : 0;

    var target = current ? scrollableAncestor(current, dir) : null;
    var opts = { left: dx, top: dy, behavior: smooth ? 'smooth' : 'auto' };
    if (target) {
      try { target.scrollBy(opts); } catch (e) { target.scrollTop += dy; target.scrollLeft += dx; }
      return true;
    }
    if (pageCanScroll(dir)) {
      try { window.scrollBy(opts); } catch (e) { window.scrollBy(dx, dy); }
      return true;
    }
    return false;
  }

  /* -------------------------------------------------------------- entering */

  /** Pick a sensible starting element: the first visible one nearest the top-left. */
  function pickInitial() {
    var list = candidates();
    var bestEl = null, bestScore = Infinity;
    for (var i = 0; i < list.length; i++) {
      var r = rectOf(list[i]);
      if (r.bottom < 0 || r.top > vh()) continue;
      var s = r.top * 2 + r.left;
      if (s < bestScore) { bestScore = s; bestEl = list[i]; }
    }
    if (!bestEl && list.length) bestEl = list[0];
    return bestEl;
  }

  /** After a scroll, re-anchor onto whatever entered the viewport. */
  function pickAfterScroll(dir) {
    var list = candidates();
    var bestEl = null, bestScore = Infinity;
    for (var i = 0; i < list.length; i++) {
      var r = rectOf(list[i]);
      if (r.bottom < 8 || r.top > vh() - 8) continue;
      var s;
      if (dir === 'down') s = r.top;
      else if (dir === 'up') s = vh() - r.bottom;
      else if (dir === 'right') s = r.left;
      else s = vw() - r.right;
      if (s < bestScore) { bestScore = s; bestEl = list[i]; }
    }
    return bestEl;
  }

  function reveal(el) {
    var r = el.getBoundingClientRect();
    var fullyVisible = r.top >= 60 && r.bottom <= vh() - 60 && r.left >= 0 && r.right <= vw();
    if (fullyVisible) return;
    try {
      el.scrollIntoView({
        block: 'center',
        inline: 'nearest',
        behavior: window.__ORBIT_SMOOTH__ ? 'smooth' : 'auto'
      });
    } catch (e) {
      try { el.scrollIntoView(); } catch (e2) {}
    }
  }

  /* ------------------------------------------------------------ activation */

  function isTextEntry(el) {
    if (!el) return false;
    var tag = (el.tagName || '').toLowerCase();
    if (tag === 'textarea') return true;
    if (el.isContentEditable) return true;
    if (tag !== 'input') return false;
    var t = (el.getAttribute('type') || 'text').toLowerCase();
    return ['text', 'search', 'url', 'email', 'tel', 'password', 'number', 'date', 'time']
      .indexOf(t) >= 0;
  }

  function fire(el, type, x, y, extra) {
    var init = {
      bubbles: true, cancelable: true, view: window,
      clientX: x, clientY: y, button: 0, buttons: extra === 'down' ? 1 : 0
    };
    var ev;
    try {
      if (type.indexOf('pointer') === 0 && window.PointerEvent) {
        init.pointerId = 1;
        init.pointerType = 'mouse';
        init.isPrimary = true;
        ev = new PointerEvent(type, init);
      } else {
        ev = new MouseEvent(type, init);
      }
    } catch (e) {
      ev = document.createEvent('MouseEvents');
      ev.initMouseEvent(type, true, true, window, 1, 0, 0, x, y,
        false, false, false, false, 0, null);
    }
    try { el.dispatchEvent(ev); } catch (e) {}
  }

  function activate() {
    if (!current) {
      var el = pickInitial();
      if (el) { mark(el); reveal(el); return { result: 'entered' }; }
      return { result: 'none' };
    }
    var el = current;
    var r = rectOf(el);
    var x = Math.round(r.cx), y = Math.round(r.cy);

    // Cross-origin frame: same-origin policy means we cannot click inside it,
    // and a captcha is the common case. Report its centre as a fraction of the
    // viewport so the app can drop a real pointer onto it.
    if ((el.tagName || '').toLowerCase() === 'iframe') {
      return {
        result: 'iframe',
        cxRatio: r.cx / (vw() || 1),
        cyRatio: r.cy / (vh() || 1)
      };
    }

    if (isTextEntry(el)) {
      try { el.focus({ preventScroll: true }); } catch (e) { try { el.focus(); } catch (e2) {} }
      return { result: 'input' };
    }

    try { el.focus({ preventScroll: true }); } catch (e) {}

    // Full pointer sequence first (many sites only listen for pointer/mouse
    // events), then a real click() so anchors and submits always fire.
    fire(el, 'pointerover', x, y);
    fire(el, 'pointerenter', x, y);
    fire(el, 'mouseover', x, y);
    fire(el, 'pointerdown', x, y, 'down');
    fire(el, 'mousedown', x, y, 'down');
    fire(el, 'pointerup', x, y);
    fire(el, 'mouseup', x, y);
    try { el.click(); } catch (e) { fire(el, 'click', x, y); }

    return { result: 'clicked', tag: (el.tagName || '').toLowerCase() };
  }

  /* ------------------------------------------------------------------- API */

  function move(dir) {
    injectStyle();
    if (!current || !document.contains(current) || !isVisible(current)) {
      var start = pickInitial();
      if (start) { mark(start); reveal(start); return { result: 'entered' }; }
      var scrolledCold = doScroll(dir, window.__ORBIT_SMOOTH__);
      return { result: scrolledCold ? 'scrolled' : 'edge' };
    }

    var next = best(dir);
    if (next) {
      mark(next);
      reveal(next);
      return { result: 'moved' };
    }

    var scrolled = doScroll(dir, window.__ORBIT_SMOOTH__);
    if (scrolled) {
      // Re-anchor after the scroll settles so focus is never left off-screen.
      window.setTimeout(function () {
        var el = pickAfterScroll(dir);
        if (el) mark(el);
      }, window.__ORBIT_SMOOTH__ ? 260 : 30);
      return { result: 'scrolled' };
    }
    return { result: 'edge' };
  }

  function info() {
    if (!current) return { has: false };
    var r = rectOf(current);
    return {
      has: true,
      tag: (current.tagName || '').toLowerCase(),
      isInput: isTextEntry(current),
      href: current.getAttribute ? (current.getAttribute('href') || '') : '',
      left: Math.round(r.left), top: Math.round(r.top),
      width: Math.round(r.width), height: Math.round(r.height)
    };
  }

  /* Media helpers — the remote's play/pause key should work on any page. */
  function videos() {
    var v = [];
    try { v = Array.prototype.slice.call(document.querySelectorAll('video')); } catch (e) {}
    return v.filter(function (x) { return x.readyState > 0 || x.currentSrc; });
  }

  function playPause() {
    var vs = videos();
    if (!vs.length) return { result: 'none' };
    var v = vs[0];
    for (var i = 0; i < vs.length; i++) if (!vs[i].paused) { v = vs[i]; break; }
    try {
      if (v.paused) { v.play(); return { result: 'playing' }; }
      v.pause();
      return { result: 'paused' };
    } catch (e) { return { result: 'error' }; }
  }

  function seek(delta) {
    var vs = videos();
    if (!vs.length) return { result: 'none' };
    var v = vs[0];
    try {
      v.currentTime = Math.max(0, Math.min((v.duration || 1e9), v.currentTime + delta));
      return { result: 'seeked', at: Math.round(v.currentTime) };
    } catch (e) { return { result: 'error' }; }
  }

  /**
   * How navigable is this page with a D-pad?
   *
   * Pages built as a single canvas, map or cross-origin frame expose almost
   * nothing to move between, and on those the pointer is the only sane input.
   * The app calls this after load to decide automatically.
   */
  function probe() {
    var list = candidates();
    var inView = 0;
    for (var i = 0; i < list.length; i++) {
      var r = rectOf(list[i]);
      if (r.bottom > 0 && r.top < vh() && r.right > 0 && r.left < vw()) inView++;
    }

    var big = 0;
    var area = (vw() * vh()) || 1;
    var frames = [];
    try {
      frames = document.querySelectorAll('iframe,canvas,embed,object');
    } catch (e) {}
    for (var j = 0; j < frames.length; j++) {
      var fr = rectOf(frames[j]);
      if ((fr.width * fr.height) / area > 0.3) big++;
    }
    return { result: 'probe', count: inView, big: big };
  }

  function rescan() {
    injectStyle();
    if (current && (!document.contains(current) || !isVisible(current))) unmark();
  }

  window.__ORBIT__ = {
    version: 1,
    move: function (d) { return JSON.stringify(move(d)); },
    activate: function () { return JSON.stringify(activate()); },
    info: function () { return JSON.stringify(info()); },
    probe: function () { return JSON.stringify(probe()); },
    playPause: function () { return JSON.stringify(playPause()); },
    seek: function (d) { return JSON.stringify(seek(d)); },
    clear: function () { unmark(); return '{}'; },
    rescan: rescan,
    enter: function () {
      injectStyle();
      var el = pickInitial();
      if (el) { mark(el); reveal(el); return JSON.stringify({ result: 'entered' }); }
      return JSON.stringify({ result: 'none' });
    }
  };

  injectStyle();

  // Single-page apps swap the whole document without a page load; drop a stale
  // highlight rather than leaving focus pointing at a detached node.
  try {
    var mo = new MutationObserver(function () {
      if (current && !document.contains(current)) unmark();
    });
    mo.observe(document.documentElement, { childList: true, subtree: true });
  } catch (e) {}
})();
