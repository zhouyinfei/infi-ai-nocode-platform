(() => {
  if (window.parent === window) return;
  let active = false, origin = '', selected = null, hovered = null;
  const overlay = document.createElement('div');
  overlay.style.cssText = 'position:fixed;pointer-events:none;z-index:2147483647;border:2px solid #8261d6;background:#8261d615;box-sizing:border-box;display:none';
  const cursor = document.createElement('style');
  cursor.textContent = '* { cursor: crosshair !important; }';
  function draw(element) {
    if (!active || !element?.isConnected) { overlay.style.display = 'none'; return; }
    const r = element.getBoundingClientRect();
    Object.assign(overlay.style, { display: 'block', left: r.left + 'px', top: r.top + 'px', width: r.width + 'px', height: r.height + 'px' });
  }
  function selector(element) {
    const parts = [];
    while (element && element !== document.documentElement) {
      if (element.id) { parts.unshift('#' + CSS.escape(element.id)); break; }
      const siblings = Array.from(element.parentElement?.children || []).filter(e => e.localName === element.localName);
      parts.unshift(element.localName + ':nth-of-type(' + (siblings.indexOf(element) + 1) + ')');
      element = element.parentElement;
    }
    return parts.join(' > ');
  }
  window.addEventListener('message', event => {
    if (event.source !== window.parent || event.data?.type !== 'infi:editor-mode') return;
    if (origin && origin !== event.origin) return;
    origin = event.origin;
    active = event.data.enabled === true;
    selected = hovered = null;
    if (active) { document.documentElement.append(overlay, cursor); }
    else { overlay.remove(); cursor.remove(); }
    draw(null);
    window.parent.postMessage({ type: 'infi:editor-ready' }, origin);
  });
  window.addEventListener('pointerover', event => {
    if (!active || !(event.target instanceof Element)) return;
    hovered = event.target;
    draw(hovered);
  }, true);
  window.addEventListener('pointerout', () => { hovered = null; draw(selected); }, true);
  for (const name of ['pointerdown', 'mousedown', 'mouseup', 'dblclick', 'submit']) {
    window.addEventListener(name, event => {
      if (active) { event.preventDefault(); event.stopImmediatePropagation(); }
    }, true);
  }
  window.addEventListener('click', event => {
    if (!active) return;
    event.preventDefault(); event.stopImmediatePropagation();
    if (!(event.target instanceof Element)) return;
    selected = event.target;
    draw(selected);
    window.parent.postMessage({ type: 'infi:editor-select', element: {
      tag: selected.localName,
      text: (selected.textContent || selected.getAttribute('alt') || '').trim().slice(0, 600),
      selector: selector(selected).slice(0, 1200),
      path: location.pathname,
    } }, origin);
  }, true);
  window.addEventListener('keydown', event => {
    if (!active) return;
    if (event.key === 'Escape') {
      selected = hovered = null; draw(null);
      window.parent.postMessage({ type: 'infi:editor-clear' }, origin);
    } else if (event.key === 'Enter' || event.key === ' ') {
      event.preventDefault(); event.stopImmediatePropagation();
    }
  }, true);
  window.addEventListener('scroll', () => draw(hovered || selected), true);
  window.addEventListener('resize', () => draw(hovered || selected));
})();
