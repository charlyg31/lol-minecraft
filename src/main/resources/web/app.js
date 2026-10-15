// Tableau de bord LolMC : profils, historique, détail des parties, classements, champions.
// Page sans dépendance, servie par l'API du plugin (ApiServer) ; navigation par #/route.
'use strict';

const app = document.getElementById('app');

// ── Clé d'API (si api.key est défini côté serveur) ────────────────
const KEY_STORE = 'lolmc.apiKey';
let apiKey = '';
try { apiKey = localStorage.getItem(KEY_STORE) || ''; } catch (e) { /* stockage indisponible */ }

// Une seule demande à la fois : plusieurs requêtes refusées en même temps attendent la même saisie
let keyPrompt = null;
function askKey() {
  keyPrompt ??= new Promise(resolve => {
    const dialog = document.getElementById('key-dialog');
    const input = document.getElementById('key-input');
    input.value = '';
    dialog.addEventListener('close', () => {
      apiKey = input.value.trim();
      try { localStorage.setItem(KEY_STORE, apiKey); } catch (e) { /* ignoré */ }
      keyPrompt = null;
      resolve();
    }, { once: true });
    dialog.showModal();
  });
  return keyPrompt;
}

async function api(path, retry = true) {
  const res = await fetch(path, { headers: apiKey ? { 'X-Api-Key': apiKey } : {} });
  if (res.status === 401 && retry) { await askKey(); return api(path, false); }
  if (res.status === 404) return null;
  if (!res.ok) throw new Error('Erreur ' + res.status);
  return res.json();
}

// ── Dictionnaires (chargés une fois) ─────────────────────────────
const dict = { champions: {}, items: {}, runes: {} };
let dictLoaded = null;
function loadDict() {
  if (!dictLoaded) {
    dictLoaded = Promise.all([api('/api/champions'), api('/api/items'), api('/api/runes')])
      .then(([champs, items, runes]) => {
        for (const c of champs || []) dict.champions[c.id] = c;
        for (const i of items || []) dict.items[i.id] = i;
        for (const r of runes || []) dict.runes[r.id] = r;
      })
      .catch(() => { dictLoaded = null; });
  }
  return dictLoaded;
}

const SPELLS = {
  FLASH: 'Flash', IGNITE: 'Embrasement', HEAL: 'Soins', BARRIER: 'Barrière', EXHAUST: 'Fatigue',
  TELEPORT: 'Téléportation', SMITE: 'Châtiment', CLEANSE: 'Purge', GHOST: 'Fantôme'
};
const EVENT_ICONS = {
  KILL: '⚔', TURRET: '🗼', INHIBITOR: '💠', NEXUS: '💥', DRAGON: '🐉', ELDER: '🐲',
  BARON: '🪱', HERALD: '👁', VOIDGRUB: '🐛'
};
const EVENT_VERBS = {
  KILL: 'a éliminé', TURRET: 'a détruit', INHIBITOR: 'a détruit', NEXUS: 'a détruit',
  DRAGON: 'a tué', ELDER: 'a tué', BARON: 'a tué', HERALD: 'a tué', VOIDGRUB: 'a tué'
};

// ── Utilitaires ───────────────────────────────────────────────────
const esc = s => String(s ?? '').replace(/[&<>"']/g, c =>
  ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const nf = new Intl.NumberFormat('fr-FR');
const num = n => nf.format(Math.round(n || 0));
const champName = id => dict.champions[id]?.name || id || '?';
const itemName = id => dict.items[id]?.name || id;
const runeName = id => dict.runes[id]?.name || id;
const time = s => `${String(Math.floor(s / 60)).padStart(2, '0')}:${String(s % 60).padStart(2, '0')}`;
const ago = ms => {
  const d = (Date.now() - ms) / 1000;
  if (d < 3600) return `il y a ${Math.max(1, Math.round(d / 60))} min`;
  if (d < 86400) return `il y a ${Math.round(d / 3600)} h`;
  return new Date(ms).toLocaleDateString('fr-FR', { day: 'numeric', month: 'short', year: 'numeric' });
};
const kda = (k, d, a) => (d === 0 ? k + a : (k + a) / d).toFixed(2);
const playerLink = (name, uuid) =>
  `<a href="#/player/${encodeURIComponent(name || uuid)}">${esc(name || uuid.slice(0, 8))}</a>`;

function render(html) { app.innerHTML = html; }
function loading() { render('<p class="empty">Chargement…</p>'); }
function fail(e) { render(`<p class="error">${esc(e.message || e)}</p>`); }

function stat(label, value) {
  return `<div class="card stat"><div class="label">${esc(label)}</div><div class="value">${value}</div></div>`;
}

function tabs(id, options, current) {
  return `<div class="tabs" role="group" id="${id}">` + options.map(([v, label]) =>
    `<button type="button" data-v="${esc(v)}" aria-pressed="${String(v) === String(current)}">${esc(label)}</button>`
  ).join('') + '</div>';
}
function onTabs(id, fn) {
  document.getElementById(id)?.addEventListener('click', e => {
    const b = e.target.closest('button[data-v]');
    if (b) fn(b.dataset.v);
  });
}

// ── Pages ─────────────────────────────────────────────────────────

async function homePage() {
  loading();
  const [status, recent, top] = await Promise.all([
    api('/api/status').catch(() => null), api('/api/matches/recent?limit=10'), api('/api/leaderboard')
  ]);
  const running = status?.instances?.filter(i => i.game_running).length || (status?.game_running ? 1 : 0);
  render(`
    <h1>Faille de l'invocateur</h1>
    <p class="muted">Statistiques des parties du serveur.</p>
    <div class="grid stats">
      ${stat('Joueurs en ligne', status ? num(status.online_players) : '—')}
      ${stat('Parties en cours', status ? num(running) : '—')}
      ${stat('Joueurs inscrits', status ? num(status.total_registered_players) : '—')}
    </div>
    <div class="grid two" style="margin-top:16px">
      <section class="card">
        <h2>Dernières parties</h2>
        ${recentList(recent)}
      </section>
      <section class="card">
        <h2>Top classé</h2>
        ${leaderboardTable((top || []).slice(0, 10), 'elo')}
        <p class="small"><a href="#/leaderboard">Classement complet →</a></p>
      </section>
    </div>`);
}

function recentList(list) {
  if (!list?.length) return '<p class="empty">Aucune partie enregistrée.</p>';
  return list.map(m => `
    <a class="match-row" href="#/match/${encodeURIComponent(m.match_id)}">
      <span class="bar" style="background:var(--${m.winner === 'RED' ? 'red' : 'blue'})"></span>
      <span class="main">
        <span class="title">Victoire ${m.winner === 'RED' ? 'Rouge' : 'Bleue'}</span>
        ${m.ranked ? '<span class="badge ranked">Classé</span>' : '<span class="badge">Normal</span>'}
        <div class="items">${time(m.duration_seconds)} · ${m.players} joueurs · ${ago(m.played_at)}</div>
      </span>
      <span class="side"><b style="color:var(--blue)">${m.blue_kills}</b> – <b style="color:var(--red)">${m.red_kills}</b></span>
    </a>`).join('');
}

function leaderboardTable(list, mode) {
  if (!list?.length) return '<p class="empty">Aucun joueur classé.</p>';
  const season = mode === 'season';
  const rows = list.map((p, i) => {
    const r = season ? p : (mode === 'normal' ? p.normal : p.ranked);
    const games = r.games, wins = r.wins;
    const wr = games ? (wins * 100 / games) : 0;
    return `<tr class="link" data-href="#/player/${encodeURIComponent(p.name)}">
      <td class="num">${i + 1}</td>
      <td>${esc(p.name)}</td>
      ${mode === 'normal' ? '' : `<td>${season ? '' : esc(p.tier) + ' · '}${num(p.elo)}</td>`}
      <td class="num">${num(wins)} / ${num(games - wins)}</td>
      <td class="num">${wr.toFixed(1)} %</td>
      <td class="num">${season ? p.kda.toFixed(2) : (mode === 'normal' ? kda(r.kills, r.deaths, r.assists) : r.kda.toFixed(2))}</td>
    </tr>`;
  }).join('');
  return `<div class="table-wrap"><table>
    <thead><tr><th class="num">#</th><th>Joueur</th>${mode === 'normal' ? '' : '<th>Elo</th>'}
      <th class="num">V / D</th><th class="num">Victoires</th><th class="num">KDA</th></tr></thead>
    <tbody>${rows}</tbody></table></div>`;
}

async function leaderboardPage(mode) {
  loading();
  const seasons = await api('/api/seasons').catch(() => null);
  const current = seasons?.current ?? 1;
  mode = mode || 'elo';
  let list;
  if (mode === 'elo') list = await api('/api/leaderboard');
  else if (mode === 'normal') list = await api('/api/leaderboard/normal');
  else list = await api('/api/leaderboard/season/' + encodeURIComponent(mode.replace('s', '')));
  const options = [['elo', 'Elo'], ['normal', 'Normal']];
  const seasonList = new Set([current, ...(seasons?.seasons || []).map(s => s.season)]);
  for (const s of [...seasonList].sort((a, b) => b - a)) options.push(['s' + s, 'Saison ' + s]);
  render(`
    <h1>Classement</h1>
    <p class="muted">Elo classé, victoires en normal, ou victoires en classé pendant une saison.</p>
    <section class="card">
      ${tabs('lb-tabs', options, mode)}
      ${leaderboardTable(list, mode.startsWith('s') ? 'season' : mode)}
    </section>`);
  onTabs('lb-tabs', v => { location.hash = '#/leaderboard/' + v; });
}

async function playerPage(name) {
  loading();
  const p = await api('/api/player/name/' + encodeURIComponent(name));
  if (!p) { render(`<p class="empty">Aucun joueur nommé « ${esc(name)} ».</p>`); return; }
  const champs = await api('/api/player-champions/' + p.uuid) || [];
  const r = p.ranked, n = p.normal;
  render(`
    <h1>${esc(p.name)}</h1>
    <p class="muted">${esc(p.tier)} · ${num(p.elo)} Elo
      ${p.honors ? ` · <span class="badge honor">🎖 ${num(p.honors)} honneur${p.honors > 1 ? 's' : ''}</span>` : ''}
      ${p.afk_games ? ` · <span class="badge afk">${num(p.afk_games)} partie${p.afk_games > 1 ? 's' : ''} AFK</span>` : ''}</p>
    <div class="grid stats">
      ${stat('Classé', `${num(r.wins)}V ${num(r.losses)}D`)}
      ${stat('Victoires (classé)', r.winrate.toFixed(1) + ' %')}
      ${stat('KDA (classé)', r.kda.toFixed(2))}
      ${stat('CS moyen', r.avg_cs.toFixed(1))}
      ${stat('Normal', `${num(n.wins)}V ${num(n.losses)}D`)}
      ${stat('Balises (classé)', num(r.wards_placed))}
    </div>
    <div class="grid two" style="margin-top:16px">
      <section class="card">
        <h2>Champions</h2>
        ${championTable(champs)}
      </section>
      <section class="card">
        <h2>Historique</h2>
        ${tabs('hist-tabs', [['', 'Toutes'], ['ranked', 'Classées'], ['normal', 'Normales']], '')}
        <div id="history"></div>
        <p><button class="btn" id="more" type="button">Plus de parties</button></p>
      </section>
    </div>`);

  let mode = '', offset = 0;
  const box = document.getElementById('history');
  const more = document.getElementById('more');
  async function load(reset) {
    if (reset) { offset = 0; box.innerHTML = ''; }
    const list = await api(`/api/match/history/${p.uuid}?limit=10&offset=${offset}&mode=${mode}`) || [];
    offset += list.length;
    if (!list.length && offset === 0) box.innerHTML = '<p class="empty">Aucune partie.</p>';
    box.insertAdjacentHTML('beforeend', list.map(historyRow).join(''));
    more.hidden = list.length < 10;
  }
  onTabs('hist-tabs', v => {
    mode = v;
    document.querySelectorAll('#hist-tabs button').forEach(b => b.setAttribute('aria-pressed', String(b.dataset.v === v)));
    load(true).catch(fail);
  });
  more.addEventListener('click', () => load(false).catch(fail));
  await load(true);
}

function championTable(list) {
  if (!list.length) return '<p class="empty">Aucune partie jouée.</p>';
  return `<div class="table-wrap"><table>
    <thead><tr><th>Champion</th><th class="num">Parties</th><th class="num">Victoires</th><th class="num">KDA</th><th class="num">CS</th></tr></thead>
    <tbody>${list.map(c => `<tr>
      <td>${esc(champName(c.champion))}</td><td class="num">${num(c.games)}</td>
      <td class="num">${c.winrate.toFixed(0)} %</td><td class="num">${c.kda.toFixed(2)}</td>
      <td class="num">${c.avg_cs.toFixed(0)}</td></tr>`).join('')}</tbody></table></div>`;
}

function historyRow(m) {
  const items = (m.items || []).map(itemName).join(', ') || 'aucun objet';
  return `<a class="match-row ${m.won ? 'win' : 'loss'}" href="#/match/${encodeURIComponent(m.match_id)}">
    <span class="bar"></span>
    <span class="main">
      <span class="title">${esc(champName(m.champion))}</span>
      <span class="badge ${m.won ? 'win' : 'loss'}">${m.won ? 'Victoire' : 'Défaite'}</span>
      ${m.ranked ? '<span class="badge ranked">Classé</span>' : ''}
      ${m.afk ? '<span class="badge afk">AFK</span>' : ''}
      ${m.honors ? `<span class="badge honor">🎖 ${m.honors}</span>` : ''}
      <div class="items">${esc(items)}</div>
    </span>
    <span class="side"><b>${m.kills} / ${m.deaths} / ${m.assists}</b>
      <div class="small muted">${m.cs} CS · ${time(m.duration_seconds)} · ${ago(m.played_at)}</div></span>
  </a>`;
}

async function matchPage(id) {
  loading();
  const m = await api('/api/match/detail/' + encodeURIComponent(id));
  if (!m) { render('<p class="empty">Partie introuvable.</p>'); return; }
  const kills = side => m[side].reduce((s, p) => s + p.kills, 0);
  const gold = side => m[side].reduce((s, p) => s + p.gold, 0);
  const teamCard = (side, label) => `
    <section class="card team ${side}">
      <header><span>${label} ${m.winner === side.toUpperCase() ? '— Victoire' : '— Défaite'}</span>
        <span>${kills(side)} kills · ${num(gold(side))} or</span></header>
      <div class="table-wrap"><table>
        <thead><tr><th>Joueur</th><th class="num">K / D / A</th><th class="num">CS</th><th class="num">Or</th>
          <th class="num">Dégâts</th><th>Objets</th><th>Runes & sorts</th></tr></thead>
        <tbody>${m[side].map(p => `<tr>
          <td><b>${esc(champName(p.champion))}</b><br>${playerLink(p.name, p.uuid)}
            ${p.afk ? ' <span class="badge afk">AFK</span>' : ''}
            ${p.honors ? ` <span class="badge honor">🎖 ${p.honors}</span>` : ''}</td>
          <td class="num">${p.kills} / ${p.deaths} / ${p.assists}<br><span class="small muted">${p.kda.toFixed(2)} KDA</span></td>
          <td class="num">${p.cs}<br><span class="small muted">${p.cs_per_min.toFixed(1)}/min</span></td>
          <td class="num">${num(p.gold)}</td>
          <td class="num">${num(p.damage_dealt)}<br><span class="small muted">${num(p.damage_taken)} subis</span></td>
          <td class="items">${esc((p.items || []).map(itemName).join(', ') || '—')}</td>
          <td class="items">${esc(p.keystone ? runeName(p.keystone) : '—')}<br>${esc((p.spells || []).map(s => SPELLS[s] || s).join(', '))}</td>
        </tr>`).join('')}</tbody>
      </table></div>
    </section>`;
  const events = m.events || [];
  render(`
    <h1>Partie du ${new Date(m.played_at).toLocaleString('fr-FR', { dateStyle: 'long', timeStyle: 'short' })}</h1>
    <p class="muted">${time(m.duration_seconds)} · ${m.ranked ? 'Classée' : 'Normale'} · Saison ${m.season}</p>
    ${teamCard('blue', 'Équipe Bleue')}
    ${teamCard('red', 'Équipe Rouge')}
    <section class="card">
      <h2>Frise de la partie</h2>
      ${events.length ? `<ul class="timeline">${events.map(e => `
        <li class="${esc(e.team)}"><span class="t">${time(e.t)}</span><span>${EVENT_ICONS[e.type] || '•'}</span>
          <span><span class="who">${esc(e.actor || 'Exécution')}</span> ${EVENT_VERBS[e.type] || ''} ${esc(e.target)}</span></li>`).join('')}</ul>`
        : '<p class="empty">Aucun événement enregistré.</p>'}
    </section>`);
}

async function championsPage(season) {
  loading();
  const seasons = await api('/api/seasons').catch(() => null);
  const list = await api('/api/champions/stats' + (season ? '?season=' + encodeURIComponent(season) : '')) || [];
  const options = [['', 'Toutes saisons'], ...(seasons?.seasons || []).map(s => [String(s.season), 'Saison ' + s.season])];
  render(`
    <h1>Champions</h1>
    <p class="muted">Taux de victoire et de sélection sur les parties enregistrées.</p>
    <section class="card">
      ${tabs('ch-tabs', options, season || '')}
      ${list.length ? `<div class="table-wrap"><table>
        <thead><tr><th>Champion</th><th class="num">Parties</th><th class="num">Sélection</th><th>Victoires</th>
          <th class="num">KDA</th><th class="num">CS/min</th><th class="num">Dégâts/min</th></tr></thead>
        <tbody>${list.map(c => `<tr>
          <td><b>${esc(champName(c.champion))}</b></td>
          <td class="num">${num(c.games)}</td>
          <td class="num">${c.pickrate.toFixed(1)} %</td>
          <td><div style="display:flex;gap:8px;align-items:center"><div class="winbar"><span style="width:${c.winrate}%"></span></div>${c.winrate.toFixed(1)} %</div></td>
          <td class="num">${c.kda.toFixed(2)}</td>
          <td class="num">${c.cs_per_min.toFixed(1)}</td>
          <td class="num">${num(c.damage_per_min)}</td></tr>`).join('')}</tbody></table></div>`
        : '<p class="empty">Aucune partie enregistrée.</p>'}
    </section>`);
  onTabs('ch-tabs', v => { location.hash = '#/champions' + (v ? '/' + v : ''); });
}

// ── Navigation ────────────────────────────────────────────────────

async function route() {
  const [, page, arg] = decodeURIComponent(location.hash.slice(1)).split('/');
  document.querySelectorAll('.topbar nav a').forEach(a =>
    a.classList.toggle('active', a.getAttribute('href') === '#/' + (page || '')));
  try {
    await loadDict();
    switch (page) {
      case 'player': await playerPage(arg); break;
      case 'match': await matchPage(arg); break;
      case 'leaderboard': await leaderboardPage(arg); break;
      case 'champions': await championsPage(arg); break;
      default: await homePage();
    }
  } catch (e) { fail(e); }
  window.scrollTo(0, 0);
}

app.addEventListener('click', e => {
  const row = e.target.closest('tr[data-href]');
  if (row && !e.target.closest('a')) location.hash = row.dataset.href;
});
document.getElementById('search').addEventListener('submit', e => {
  e.preventDefault();
  const q = document.getElementById('search-input').value.trim();
  if (q) location.hash = '#/player/' + encodeURIComponent(q);
});
window.addEventListener('hashchange', route);
route();
