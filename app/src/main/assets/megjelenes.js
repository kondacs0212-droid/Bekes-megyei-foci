/* Csak a felület megjelenését módosítja. Firebase / admin / értesítés logikát nem érint. */
(function () {
  function span(cls, value) {
    const el = document.createElement('span');
    el.className = cls;
    el.textContent = value;
    return el;
  }
  function updateAppearance() {
    document.querySelectorAll('#matches article.card').forEach(function (card) {
      const info = card.querySelector(':scope > .muted');
      const state = (info ? info.textContent : '').split('·').pop().trim();
      card.classList.toggle('is-live', state === 'Élő');
      const score = card.querySelector(':scope > .score');
      if (!score || score.dataset.styledScore === 'yes') return;
      const original = score.textContent.trim();
      const match = original.match(/^(.*?)\s+(\d+)\s*:\s*(\d+)\s+(.*?)$/);
      if (!match) return;
      score.replaceChildren(
        span('team-name', match[1]),
        span('score-numbers', match[2] + ' : ' + match[3]),
        span('team-name', match[4])
      );
      score.dataset.styledScore = 'yes';
    });
    document.querySelectorAll('nav button').forEach(function (button) {
      const match = (button.getAttribute('onclick') || '').match(/tab\(['"]([^'"]+)/);
      if (!match) return;
      const section = document.getElementById(match[1]);
      button.classList.toggle('active', !!section && !section.classList.contains('hidden'));
    });
  }
  document.addEventListener('DOMContentLoaded', function () {
    const root = document.querySelector('main');
    if (!root) return;
    let pending = false;
    const observer = new MutationObserver(function () {
      if (pending) return;
      pending = true;
      requestAnimationFrame(function () { pending = false; updateAppearance(); });
    });
    updateAppearance();
    observer.observe(root, {childList:true, subtree:true, attributes:true, attributeFilter:['class']});
  });
})();
