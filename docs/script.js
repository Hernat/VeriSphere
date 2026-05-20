/* ============================================================
   VeriSphere landing — vanilla JS, ~110 lines, zero deps.
   Behaviours:
     1. Theme toggle (light/dark) with localStorage persistence
        and system preference fallback.
     2. Smooth scroll for in-page anchors (browser default + offset
        for the sticky topbar).
     3. Scroll reveal via IntersectionObserver, disabled under
        prefers-reduced-motion.
     4. Inline sprite injection — workaround for the file:// case
        where <use href="external.svg#id"/> fails CORS on some
        browsers; we fetch the sprite once and inline it.
   ============================================================ */

(() => {
  'use strict';

  const root = document.documentElement;
  const STORAGE_KEY = 'verisphere-theme';
  const REDUCED_MOTION = window.matchMedia('(prefers-reduced-motion: reduce)').matches;

  // ----- 1. Theme handling ---------------------------------------------
  const applyTheme = (theme) => {
    if (theme === 'dark') {
      root.setAttribute('data-theme', 'dark');
    } else {
      root.removeAttribute('data-theme');
    }
    updateToggleIcon(theme);
  };

  const updateToggleIcon = (theme) => {
    const btn = document.getElementById('theme-toggle');
    if (!btn) return;
    const useEl = btn.querySelector('use');
    if (!useEl) return;
    useEl.setAttribute('href', `assets/icons.svg#${theme === 'dark' ? 'i-sun' : 'i-moon'}`);
    btn.setAttribute('aria-label', theme === 'dark'
      ? 'Basculer vers le thème clair'
      : 'Basculer vers le thème sombre');
  };

  const initTheme = () => {
    const stored = localStorage.getItem(STORAGE_KEY);
    const systemDark = window.matchMedia('(prefers-color-scheme: dark)').matches;
    const initial = stored || (systemDark ? 'dark' : 'light');
    applyTheme(initial);

    document.getElementById('theme-toggle')?.addEventListener('click', () => {
      const current = root.getAttribute('data-theme') === 'dark' ? 'dark' : 'light';
      const next = current === 'dark' ? 'light' : 'dark';
      applyTheme(next);
      localStorage.setItem(STORAGE_KEY, next);
    });
  };

  // ----- 2. Scroll reveal ----------------------------------------------
  const initScrollReveal = () => {
    if (REDUCED_MOTION || !('IntersectionObserver' in window)) {
      document.querySelectorAll('.reveal').forEach(el => el.classList.add('visible'));
      return;
    }
    const observer = new IntersectionObserver((entries, obs) => {
      entries.forEach(entry => {
        if (entry.isIntersecting) {
          entry.target.classList.add('visible');
          obs.unobserve(entry.target);
        }
      });
    }, { rootMargin: '0px 0px -80px 0px', threshold: 0.05 });

    document.querySelectorAll('.reveal').forEach(el => observer.observe(el));
  };

  // ----- 3. Inline SVG sprite (file:// CORS workaround) ----------------
  const inlineSprite = async () => {
    const host = document.getElementById('svg-sprite-host');
    if (!host) return;
    try {
      const res = await fetch('assets/icons.svg');
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      const text = await res.text();
      host.innerHTML = text;
      // Rewrite all <use href="assets/icons.svg#id"> to "#id" so they
      // resolve against the inlined sprite (faster + works on file://).
      document.querySelectorAll('use[href^="assets/icons.svg#"]').forEach(use => {
        const id = use.getAttribute('href').split('#')[1];
        use.setAttribute('href', `#${id}`);
      });
    } catch (e) {
      // Non-blocking — external href usage will still work over http(s)
      console.warn('[VeriSphere] sprite inline skipped:', e.message);
    }
  };

  // ----- 4. Smooth scroll for in-page anchors --------------------------
  const initSmoothScroll = () => {
    document.querySelectorAll('a[href^="#"]:not([href="#"])').forEach(link => {
      link.addEventListener('click', (e) => {
        const targetId = link.getAttribute('href').slice(1);
        const target = document.getElementById(targetId);
        if (!target) return;
        e.preventDefault();
        const topbarHeight = document.querySelector('.topbar')?.offsetHeight ?? 0;
        const y = target.getBoundingClientRect().top + window.scrollY - topbarHeight - 8;
        window.scrollTo({
          top: y,
          behavior: REDUCED_MOTION ? 'auto' : 'smooth',
        });
      });
    });
  };

  // ----- Bootstrap -----------------------------------------------------
  document.addEventListener('DOMContentLoaded', () => {
    initTheme();
    initScrollReveal();
    initSmoothScroll();
    inlineSprite();
  });
})();
