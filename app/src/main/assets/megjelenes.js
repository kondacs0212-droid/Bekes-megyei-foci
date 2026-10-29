/* Csak a mérkőzéskártyák külsejét rendezi át; a működő eseménykezelőket nem módosítja. */
(function(){
  function el(tag,cls,text){const n=document.createElement(tag);n.className=cls;if(text!==undefined)n.textContent=text;return n;}
  function decorate(){
    document.querySelectorAll('#matches article.card').forEach(card=>{
      const info=card.querySelector(':scope > .muted');
      const state=(info?.textContent||'').split('·').pop().trim();
      card.classList.toggle('is-live',state==='Élő');
      if(card.dataset.decorated==='1')return;
      const score=card.querySelector(':scope > .score');
      const clock=card.querySelector(':scope > div:not(.muted):not(.score) .matchclock');
      if(!score||!clock)return;
      const match=score.textContent.trim().match(/^(.*?)\s+(\d+)\s*:\s*(\d+)\s+(.*?)$/s);
      if(!match)return;
      card.dataset.decorated='1';
      const top=el('div','match-top');
      if(state==='Élő')top.append(el('span','live-badge','● ÉLŐ'));
      const oldClockContainer=clock.parentElement;
      top.append(clock);
      oldClockContainer.replaceWith(top);
      score.replaceChildren(el('span','team-name',match[1].trim()),el('span','score-numbers',match[2]+' : '+match[3]),el('span','team-name',match[4].trim()));
      const controls=el('div','match-controls');
      while(score.nextElementSibling?.tagName==='BUTTON')controls.append(score.nextElementSibling);
      if(controls.children.length)score.after(controls);
    });
    document.querySelectorAll('nav button').forEach(button=>{
      const match=(button.getAttribute('onclick')||'').match(/tab\(['"]([^'"]+)/);
      if(match){const section=document.getElementById(match[1]);button.classList.toggle('active',!!section&&!section.classList.contains('hidden'));}
    });
  }
  function init(){const root=document.querySelector('main');if(!root)return;let queued=false;const observer=new MutationObserver(()=>{if(queued)return;queued=true;requestAnimationFrame(()=>{queued=false;decorate();});});decorate();observer.observe(root,{childList:true,subtree:true,attributes:true,attributeFilter:['class']});}
  if(document.readyState==='loading')document.addEventListener('DOMContentLoaded',init);else init();
})();
