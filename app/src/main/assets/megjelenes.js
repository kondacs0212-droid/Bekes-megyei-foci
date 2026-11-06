/* Csak a mérkőzéskártyák külsejét rendezi át; a működő eseménykezelőket nem módosítja. */
(function(){
  function el(tag,cls,text){const n=document.createElement(tag);n.className=cls;if(text!==undefined)n.textContent=text;return n;}
  // Csak a kilenc deli csoport csapatcimere. Minden mas funkcio valtozatlan.
  const crestFiles={
    'csardaszallasi':'csardaszallasi.png',
    'csorvasi':'csorvasi.png',
    'kardos':'kardos_ormenykut.png',
    'kevermes':'kevermes.png',
    'kunagotai':'kunagotai.png',
    'medgyesbodzas':'medgyesbodzas.png',
    'medgyesegyhaza':'medgyesegyhaza.png',
    'mezomegyer':'mezomegyer_ii.png',
    'telekgerendasi':'telekgerendasi.png'
  };
  function crestFor(name){
    const key=String(name||'').toLocaleLowerCase('hu').normalize('NFD').replace(/[\u0300-\u036f]/g,'');
    const prefix=Object.keys(crestFiles).find(k=>key.includes(k));
    return prefix?'cimerek/'+crestFiles[prefix]:null;
  }
  function teamWithCrest(name){
    const team=el('span','team-name',name);
    const src=crestFor(name);
    if(src){
      const img=document.createElement('img');
      img.className='team-crest-img';img.src=src;img.alt=name+' cimere';
      img.onerror=()=>{img.remove();team.classList.remove('crest-loaded');};
      team.prepend(img);team.classList.add('crest-loaded');
    }
    return team;
  }
  const crestStyle=document.createElement('style');
  crestStyle.textContent='#matches article.card .team-name.crest-loaded::before{content:none!important;display:none!important}#matches article.card .team-crest-img{display:block;width:clamp(54px,16vw,82px);height:clamp(54px,16vw,82px);object-fit:contain;flex:none}';
  document.head.append(crestStyle);
  function decorate(){
    document.querySelectorAll('#matches article.card').forEach(card=>{
      const info=card.querySelector(':scope > .muted');
      const state=(info?.textContent||'').split('·').pop().trim();
      card.classList.toggle('is-live',state==='Élő');
      // Élő meccs és félidő alatt a részletek mindig nyitva maradnak.
      const details=card.querySelector('details[data-match]');
      if(details && (state==='Élő'||state==='Félidő') && !details.open){details.open=true;}
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
      score.replaceChildren(teamWithCrest(match[1].trim()),el('span','score-numbers',match[2]+' : '+match[3]),teamWithCrest(match[4].trim()));
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
