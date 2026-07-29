/*
 * Orbit reader mode.
 *
 * A compact readability pass: score block elements by how much real paragraph
 * text they contain, take the winner, and re-render it as a single clean
 * column. On a TV this matters more than on a phone — body text at three metres
 * needs large type and short measure, which almost no site provides.
 *
 * Toggling off restores the original document, so it is non-destructive.
 */
(function () {
  'use strict';

  if (window.__ORBIT_READER__) {
    window.__ORBIT_READER_RESULT__ = window.__ORBIT_READER__.toggle();
    return;
  }

  var STRIP = /(^|[\s\-_])(comment|share|footer|header|nav|menu|sidebar|promo|banner|advert|social|related|newsletter|cookie|subscribe|paywall|modal|popup)([\s\-_]|$)/i;

  function textLen(el) {
    return (el.innerText || el.textContent || '').trim().length;
  }

  function looksLikeChrome(el) {
    var id = el.id || '';
    var cls = (typeof el.className === 'string') ? el.className : '';
    return STRIP.test(id) || STRIP.test(cls);
  }

  /** Score = paragraph text, penalised by link density and boilerplate naming. */
  function score(el) {
    var paras = el.querySelectorAll('p');
    if (paras.length < 2) return 0;

    var total = 0;
    for (var i = 0; i < paras.length; i++) {
      var t = textLen(paras[i]);
      if (t > 40) total += t;
    }
    if (total < 250) return 0;

    var linkText = 0;
    var links = el.querySelectorAll('a');
    for (var j = 0; j < links.length; j++) linkText += textLen(links[j]);

    var all = textLen(el) || 1;
    var linkDensity = linkText / all;
    if (linkDensity > 0.5) return 0;

    var s = total * (1 - linkDensity);
    if (looksLikeChrome(el)) s *= 0.25;
    if (el.tagName === 'ARTICLE') s *= 1.6;
    if (el.getAttribute && el.getAttribute('role') === 'main') s *= 1.4;
    if (el.tagName === 'MAIN') s *= 1.4;
    return s;
  }

  function findArticle() {
    var candidates = document.querySelectorAll(
      'article, main, [role="main"], div, section, td'
    );
    var best = null, bestScore = 0;
    for (var i = 0; i < candidates.length; i++) {
      var s = score(candidates[i]);
      if (s > bestScore) { bestScore = s; best = candidates[i]; }
    }
    return best;
  }

  function titleOf() {
    var h1 = document.querySelector('h1');
    if (h1 && textLen(h1) > 3) return h1.innerText.trim();
    return (document.title || '').trim();
  }

  function bylineOf() {
    var sel = ['[rel="author"]', '.byline', '.author', '[itemprop="author"]'];
    for (var i = 0; i < sel.length; i++) {
      var el = document.querySelector(sel[i]);
      if (el && textLen(el) > 2 && textLen(el) < 120) return el.innerText.trim();
    }
    return '';
  }

  var STYLE = [
    '#__orbit_reader{',
    'position:fixed;inset:0;z-index:2147483000;overflow:auto;',
    'background:#0F1216;color:#E8ECF2;',
    'font-family:Georgia,"Noto Serif",serif;',
    '-webkit-font-smoothing:antialiased;',
    '}',
    '#__orbit_reader .rd{max-width:46rem;margin:0 auto;padding:9vh 6vw 12vh;}',
    '#__orbit_reader h1{font-size:2.6rem;line-height:1.18;margin:0 0 .6rem;',
    'font-family:"Roboto",system-ui,sans-serif;font-weight:700;letter-spacing:-.01em;}',
    '#__orbit_reader .meta{font-family:"Roboto",system-ui,sans-serif;font-size:.95rem;',
    'color:#9AA4B2;margin-bottom:2.4rem;padding-bottom:1.4rem;border-bottom:1px solid #2A313C;}',
    '#__orbit_reader p{font-size:1.42rem;line-height:1.72;margin:0 0 1.5rem;}',
    '#__orbit_reader li{font-size:1.42rem;line-height:1.72;margin-bottom:.7rem;}',
    '#__orbit_reader h2{font-size:1.9rem;margin:2.6rem 0 1rem;',
    'font-family:"Roboto",system-ui,sans-serif;}',
    '#__orbit_reader h3{font-size:1.55rem;margin:2.2rem 0 .9rem;',
    'font-family:"Roboto",system-ui,sans-serif;}',
    '#__orbit_reader img{max-width:100%;height:auto;border-radius:10px;margin:1.6rem 0;}',
    '#__orbit_reader figure{margin:1.8rem 0;}',
    '#__orbit_reader figcaption{font-size:1rem;color:#9AA4B2;',
    'font-family:"Roboto",system-ui,sans-serif;margin-top:.6rem;}',
    '#__orbit_reader a{color:#6AA6FF;}',
    '#__orbit_reader blockquote{margin:1.8rem 0;padding-left:1.4rem;',
    'border-left:4px solid #2A313C;color:#B9C2CE;font-style:italic;}',
    '#__orbit_reader pre{background:#161A21;padding:1.1rem;border-radius:10px;',
    'overflow-x:auto;font-size:1.05rem;}',
    '#__orbit_reader code{font-family:"Roboto Mono",monospace;font-size:.95em;}'
  ].join('');

  var overlay = null;

  function build() {
    var article = findArticle();
    if (!article) return false;

    var clone = article.cloneNode(true);
    var junk = clone.querySelectorAll(
      'script,style,noscript,iframe,form,button,input,select,textarea,svg,video,audio,' +
      'nav,aside,footer,header'
    );
    for (var i = 0; i < junk.length; i++) {
      if (junk[i].parentNode) junk[i].parentNode.removeChild(junk[i]);
    }
    var maybe = clone.querySelectorAll('div,section,ul,ol');
    for (var j = 0; j < maybe.length; j++) {
      if (looksLikeChrome(maybe[j]) && maybe[j].parentNode) {
        maybe[j].parentNode.removeChild(maybe[j]);
      }
    }

    var style = document.createElement('style');
    style.textContent = STYLE;

    overlay = document.createElement('div');
    overlay.id = '__orbit_reader';

    var wrap = document.createElement('div');
    wrap.className = 'rd';

    var h = document.createElement('h1');
    h.textContent = titleOf();

    var meta = document.createElement('div');
    meta.className = 'meta';
    var by = bylineOf();
    meta.textContent = (by ? by + '  ·  ' : '') + location.hostname.replace(/^www\./, '');

    wrap.appendChild(h);
    wrap.appendChild(meta);
    wrap.appendChild(clone);
    overlay.appendChild(style);
    overlay.appendChild(wrap);
    document.documentElement.appendChild(overlay);
    document.documentElement.style.overflow = 'hidden';
    overlay.scrollTop = 0;
    return true;
  }

  function teardown() {
    if (overlay && overlay.parentNode) overlay.parentNode.removeChild(overlay);
    overlay = null;
    document.documentElement.style.overflow = '';
  }

  window.__ORBIT_READER__ = {
    active: false,
    toggle: function () {
      if (this.active) {
        teardown();
        this.active = false;
        return JSON.stringify({ result: 'off' });
      }
      if (!build()) return JSON.stringify({ result: 'unavailable' });
      this.active = true;
      // Reader content is plain text, so spatial navigation has little to grab;
      // scrolling is what matters here.
      if (window.__ORBIT__) window.__ORBIT__.clear();
      return JSON.stringify({ result: 'on' });
    },
    scrollBy: function (dy) {
      if (overlay) overlay.scrollBy({ top: dy, behavior: 'smooth' });
      return '{}';
    }
  };

  window.__ORBIT_READER_RESULT__ = window.__ORBIT_READER__.toggle();
})();
