// Screen tests in a real browser, offline: both pages, every card, card-only mode, old settings links.
//
//   npm install playwright@1.56.1 && npx playwright install chromium     (once)
//   node tests/page.test.mjs
//
// Network is cut; the photos and fonts the app serves from the device are served from the repo instead,
// the way PhotoCache.java answers them on the TV.
import { chromium } from 'playwright';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const page = name => 'file://' + path.join(ROOT, 'dist', name);
const INDEX = readFileSync(path.join(ROOT, 'photos/index.json'), 'utf8');
let failed = 0;
const check = (name, ok, detail = '') => {
  console.log((ok ? 'PASS ' : 'FAIL ') + name + (ok ? '' : '  -> ' + detail));
  if (!ok) failed++;
};

const browser = await chromium.launch(process.env.CHROMIUM ? { executablePath: process.env.CHROMIUM } : {});

async function open(file, hash, { width = 1920, height = 1080, seed = true } = {}) {
  const ctx = await browser.newContext({ viewport: { width, height } });
  await ctx.route(/^https?:/, r => {
    const u = r.request().url(), i = u.indexOf('/master/'), rel = i > 0 ? u.slice(i + 8) : '';
    if (/^(assets\/(backgrounds|fonts)|photos)\//.test(rel))
      return r.fulfill({ path: path.join(ROOT, rel), headers: { 'Access-Control-Allow-Origin': '*' } });
    return r.abort();
  });
  if (seed) await ctx.addInitScript(i => { try { localStorage.setItem('photoIndex', i); } catch (e) {} }, INDEX);
  const p = await ctx.newPage();
  const errors = [];
  p.on('pageerror', e => errors.push(e.message));
  await p.goto(page(file) + '#' + hash);
  await p.waitForFunction(() => document.documentElement.classList.contains('ready'), null, { timeout: 4000 })
    .catch(() => errors.push('never revealed'));
  await p.waitForTimeout(300);
  return { p, ctx, errors };
}

// ---- both pages, three screen sizes: no errors, shown, clock right, nothing off screen, Arabic digits
for (const file of ['urtv.html', 'tv.html']) {
  for (const [w, h] of [[1920, 1080], [1280, 720], [960, 540]]) {
    const { p, ctx, errors } = await open(file, 'city=جدة&lat=21.5433&lon=39.1728', { width: w, height: h });
    const tag = `${file} ${w}x${h}`;
    check(`${tag}: no script errors, page shown`, !errors.length, errors.join('; '));
    const r = await p.evaluate(() => {
      const hh = document.getElementById('ck-h').textContent, mm = document.getElementById('ck-m').textContent;
      const toN = s => +s.replace(/[٠-٩]/g, d => '٠١٢٣٤٥٦٧٨٩'.indexOf(d));
      const off = [...document.querySelectorAll('.stage *')].filter(e => {
        const b = e.getBoundingClientRect(), cs = getComputedStyle(e);
        return cs.display !== 'none' && b.width && (b.left < -2 || b.right > innerWidth + 2 || b.bottom > innerHeight + 2);
      }).map(e => e.className || e.id || e.tagName).slice(0, 5);
      const latin = (document.querySelector('.stage').innerText.match(/[0-9]+/g) || []).filter(x => x !== '500');
      return { hh, mm, h: toN(hh), m: toN(mm), ap: document.getElementById('ck-ap').textContent,
               sub: document.getElementById('clock-sub').textContent, off, latin,
               photo: !!document.querySelector('.photo').style.backgroundImage };
    });
    check(`${tag}: 12-hour clock in Arabic digits, two-digit hours`, /^[٠-٩]{2}$/.test(r.hh) && r.h >= 1 && r.h <= 12
      && /^[٠-٩]{2}$/.test(r.mm) && r.m < 60 && ['ص', 'م'].includes(r.ap), JSON.stringify(r));
    check(`${tag}: city from the settings`, r.sub === 'بتوقيت جدة', r.sub);
    check(`${tag}: nothing off screen`, !r.off.length, r.off.join(', '));
    check(`${tag}: no Latin digits (except S&P 500)`, !r.latin.length, r.latin.join(' '));
    check(`${tag}: a background photo`, r.photo);
    await ctx.close();
  }
}

// ---- every card beside the clock, with sample data where the card needs the network
const SAMPLE = {
  forecast: () => { FORECAST = [0, 1, 2, 3, 4].map(i => ({ date: new Date(Date.now() + i * 864e5).toISOString().slice(0, 10), code: 2, hi: 37 - i, lo: 24 - i })); },
  matches: () => {
    const ev = (id, h, a, hrs) => ({ idEvent: id, strHomeTeam: h, strAwayTeam: a, strTimestamp: new Date(Date.now() + hrs * 36e5).toISOString() });
    NEXT_LIST = { '136013': [ev('a', 'Al-Hilal', 'Al-Nassr', 3), ev('b', 'Al-Ahli', 'Al-Hilal', 80)] };
  },
};
const EXPECT = { prayers: [6, 'مواقيت الصلاة'], forecast: [5, 'الطقس'], matches: [2, 'مباريات فرقك القادمة'],
                 verse: [2, null] };
for (const [panel, [rows, title]] of Object.entries(EXPECT)) {
  const { p, ctx, errors } = await open('tv.html', 'teams=136013&panel=' + panel);
  if (SAMPLE[panel]) await p.evaluate(`(${SAMPLE[panel]})(); renderPanel(false);`);
  const r = await p.evaluate(() => ({ n: document.querySelectorAll('#week-list li').length,
    hd: document.getElementById('week-hd').textContent, shown: getComputedStyle(document.querySelector('.week')).display !== 'none' }));
  check(`card ${panel}: no errors`, !errors.length, errors.join('; '));
  check(`card ${panel}: ${rows} rows, title right`, r.shown && r.n === rows && (!title || r.hd === title)
    && (title || ['آية اليوم', 'حديث اليوم'].includes(r.hd)), JSON.stringify(r));
  await ctx.close();
}
{
  const { p, ctx } = await open('tv.html', 'panel=verse');
  const both = await p.evaluate(() => {
    const day = riyadhDayNumber, out = [];
    for (const k of [0, 1]) { window.riyadhDayNumber = () => day() + k; out.push(quoteOfDay().title); }
    return out.sort().join(',');
  });
  check('card verse: a verse one day, a hadith the next', both === 'آية اليوم,حديث اليوم', both);
  await ctx.close();
}

// ---- every verse and hadith fits the card: each day's text drawn, clear of the clock and the bottom bar
for (const [width, height] of [[1920, 1080], [1280, 720], [960, 540]]) {
  const { p, ctx, errors } = await open('tv.html', 'panel=verse', { width, height });
  const r = await p.evaluate(() => {
    const day = riyadhDayNumber(), card = document.querySelector('.week'), bad = [];
    const days = 2 * Math.max(VERSES.length, HADITH.length);   // a verse on even days, a hadith on odd: all of both
    let tallest = 0;
    for (let k = 0; k < days; k++) {
      window.riyadhDayNumber = () => day + k;
      renderPanel(false);
      const c = card.getBoundingClientRect(), clock = document.querySelector('.clockwrap').getBoundingClientRect();
      const bar = document.querySelector('.bottom').getBoundingClientRect();
      tallest = Math.max(tallest, c.height);
      const top = document.querySelector('.top').getBoundingClientRect();
      // a card beside the clock, not a page: clear of the top line, the clock and the bottom bar, at most 42% high
      if (c.top < top.bottom || c.bottom > bar.top - 4 || c.right > clock.left - 4 || c.height > 0.42 * innerHeight)
        bad.push(`${Math.round(c.top)}-${Math.round(c.bottom)}/${Math.round(bar.top)} ` + card.querySelector('.quote').textContent.slice(0, 40));
    }
    return { days, bad, tallest: Math.round(tallest) };
  });
  check(`every verse and hadith fits the card at ${width}x${height} (${r.days} days, tallest ${r.tallest}px)`,
    !errors.length && !r.bad.length, `${r.bad.length} spill: ` + r.bad.slice(0, 3).join(' | ') + errors.join('; '));
  await ctx.close();
}

// ---- photo interval: #photos=N (5 10 15 30 60), anything else 30; the photo changes on that slot
for (const [hash, want] of [['', 30], ['photos=5', 5], ['photos=60', 60], ['photos=7', 30], ['photos=abc', 30], ['photos=0', 30]]) {
  const { p, ctx, errors } = await open('tv.html', hash);
  const r = await p.evaluate(() => {
    const real = riyadhParts;
    const at = (h, mi) => { window.riyadhParts = () => ({ ...real(), h, mi }); BG_SLOT = -1; applyBackground(true); return BG_SLOT; };
    return [CONFIG.photoMinutes, at(10, 4), at(10, 5), at(10, 29), at(10, 30), at(10, 59), at(11, 0)];
  });
  const per = want, changes = [[4, 5], [29, 30], [59, 60]].map(([a, b]) => Math.floor(a / per) !== Math.floor(b / per));
  const got = [r[1] !== r[2], r[3] !== r[4], r[5] !== r[6]];
  check(`link #${hash || '(empty)'} → CONFIG.photoMinutes ${want}, photo changes on that slot`,
    !errors.length && r[0] === want && JSON.stringify(got) === JSON.stringify(changes), JSON.stringify([r, changes]) + errors.join('; '));
  await ctx.close();
}

// ---- old settings links and «لا شيء»
for (const [hash, panel, shown] of [['', 'auto', true], ['week=0', 'none', false], ['week=1', 'auto', true],
                                     ['panel=none', 'none', false], ['panel=bogus', 'auto', true], ['panel=verse', 'verse', true]]) {
  const { p, ctx, errors } = await open('tv.html', hash);
  const r = await p.evaluate(() => [CONFIG.panel, getComputedStyle(document.querySelector('.week')).display !== 'none']);
  check(`link #${hash || '(empty)'} → ${panel}, card ${shown ? 'shown' : 'hidden'}`,
    !errors.length && r[0] === panel && r[1] === shown, JSON.stringify(r) + errors.join('; '));
  await ctx.close();
}

// ---- card-only mode for the launcher: transparent, only the card, no photo
for (const panel of ['prayers', 'verse']) {
  const { p, ctx, errors } = await open('tv.html', `widget=1&panel=${panel}`, { width: 461, height: 250 });
  const r = await p.evaluate(() => ({
    bg: getComputedStyle(document.body).backgroundColor,
    visible: [...document.querySelectorAll('.top,.clockwrap,.bottom,.photo')].filter(e => getComputedStyle(e).display !== 'none').length,
    card: document.querySelector('.week').getBoundingClientRect().width }));
  check(`launcher card ${panel}: transparent, only the card`, !errors.length && r.bg === 'rgba(0, 0, 0, 0)'
    && r.visible === 0 && r.card > 400, JSON.stringify(r) + errors.join('; '));
  await ctx.close();
}

// ---- «auto»: the four cards in turn every 30 s; a card with nothing to show is skipped
{
  const { p, ctx, errors } = await open('tv.html', 'teams=136013&panel=auto');
  const seq = await p.evaluate(async () => {
    FORECAST = [0, 1, 2].map(i => ({ date: new Date(Date.now() + i * 864e5).toISOString().slice(0, 10), code: 2, hi: 30, lo: 20 }));
    NEXT_LIST = {};   // no games this week: the matches card is skipped
    const out = [document.getElementById('week-hd').textContent];
    for (let i = 0; i < 4; i++) {   // each change fades out, then in (380 ms)
      rotateCard();
      await new Promise(r => setTimeout(r, 450));
      out.push(document.getElementById('week-hd').textContent);
    }
    return out;
  });
  const verse = t => ['آية اليوم', 'حديث اليوم'].includes(t);
  check('auto: verse → prayers → forecast → (no games: skipped) → verse', !errors.length && verse(seq[0])
        && seq[1] === 'مواقيت الصلاة' && seq[2] === 'الطقس' && verse(seq[3]) && seq[4] === 'مواقيت الصلاة', JSON.stringify(seq));
  const every = await p.evaluate(() => ROTATE_MS);
  check('auto: every 30 seconds', every === 30000, every);
  await ctx.close();
}
{
  const { p, ctx } = await open('tv.html', 'panel=prayers');
  const r = await p.evaluate(async () => { const a = document.getElementById('week-hd').textContent; rotateCard();
    await new Promise(r => setTimeout(r, 450)); return [a, document.getElementById('week-hd').textContent]; });
  check('a single chosen card does not rotate', r[0] === 'مواقيت الصلاة' && r[1] === 'مواقيت الصلاة', JSON.stringify(r));
  await ctx.close();
}

// ---- night dimming: off unless #night=1 (it darkened the screen at night by itself before)
for (const [hash, dimmed] of [['', false], ['night=0', false], ['night=1', true]]) {
  const { p, ctx } = await open('tv.html', hash);
  const o = await p.evaluate(() => { window.riyadhParts = () => ({ y: 2026, mo: 10, d: 24, h: 2, mi: 0, s: 0 }); applyNight();
                                     return +document.getElementById('dimmer').style.opacity; });
  check(`night dimming at 2 am, #${hash || '(none)'}: ${dimmed ? 'dark' : 'full brightness'}`, dimmed ? o > 0.3 : o === 0, o);
  await ctx.close();
}

// ---- the card is glass: the photo behind it, blurred, lined up with the photo on screen
{
  const { p, ctx } = await open('tv.html', 'panel=verse');
  const r = await p.evaluate(() => {
    const w = document.querySelector('.week'), b = getComputedStyle(w, '::before'), rect = w.getBoundingClientRect();
    return { photo: b.backgroundImage.startsWith('url('), blur: b.filter.includes('blur'), radius: getComputedStyle(w).borderRadius,
             x: parseFloat(w.style.getPropertyValue('--bgx')), left: rect.left };
  });
  check('card: glass over the blurred photo, lined up', r.photo && r.blur && r.radius === '24px' && Math.abs(r.x - (40 - r.left)) < 1, JSON.stringify(r));
  await ctx.close();
}

// ---- the prayer card: «مواقيت الصلاة», times as ٠١:٠٣, a live countdown on the next prayer, black outline
{
  const { p, ctx, errors } = await open('tv.html', 'panel=prayers');
  const r = await p.evaluate(async () => {
    window.riyadhParts = () => ({ y: 2026, mo: 10, d: 24, h: 13, mi: 0, s: 15 });
    DAY_TIMES = { Fajr: '05:10', Sunrise: '07:00', Dhuhr: '13:03', Asr: '15:40', Maghrib: '18:20', Isha: '20:05' };
    renderPanel(false);
    const li = document.querySelector('.week li.next');
    const before = li.querySelector('.cd').textContent;
    window.riyadhParts = () => ({ y: 2026, mo: 10, d: 24, h: 13, mi: 0, s: 16 });
    tickCountdown();
    return { hd: document.getElementById('week-hd').textContent, rows: [...document.querySelectorAll('.week li')].map(x => x.textContent),
             before, after: li.querySelector('.cd').textContent, shadow: getComputedStyle(document.querySelector('.week')).textShadow };
  });
  check('prayer card: titled «مواقيت الصلاة»', !errors.length && r.hd === 'مواقيت الصلاة', JSON.stringify(r));
  check('prayer card: times as ٠١:٠٣ (two-digit hours)', r.rows[0].includes('٠٥:١٠ ص') && r.rows[2].includes('٠١:٠٣ م'), JSON.stringify(r.rows));
  check('prayer card: the next prayer counts down between its name and its time, every second',
        r.before === 'بعد ٠٠:٠٢:٤٥' && r.after === 'بعد ٠٠:٠٢:٤٤' && (r.rows[2].startsWith('الظهر') || r.rows[2].startsWith('الجمعة')), JSON.stringify(r));
  check('prayer card: words outlined in black', /rgb\(0, 0, 0\)/.test(r.shadow), r.shadow);
  await ctx.close();
}

// ---- football-data.org (#fdkey): Barcelona's next five games, home and away, curated Arabic names; key in a header
{
  const ctx = await browser.newContext({ viewport: { width: 1920, height: 1080 } });
  const calls = [], tsdb = [];
  const day = n => new Date(Date.now() + n * 864e5).toISOString();
  const m = (id, h, a, n, st, sc) => ({ id, utcDate: day(n), status: st, homeTeam: h, awayTeam: a,
                                        score: { fullTime: sc ? { home: sc[0], away: sc[1] } : { home: null, away: null } } });
  const BAR = { id: 81, name: 'FC Barcelona', shortName: 'Barça' }, RMA = { id: 86, name: 'Real Madrid CF', shortName: 'Real Madrid' };
  const OPP = n => ({ id: 900 + n, name: 'Opponent ' + n, shortName: 'Opponent ' + n });
  await ctx.route(/^https?:/, r => {
    const u = new URL(r.request().url()), cors = { 'Access-Control-Allow-Origin': '*', 'Access-Control-Allow-Headers': 'X-Auth-Token' };
    if (u.host === 'api.football-data.org') {
      calls.push({ path: u.pathname + u.search, key: r.request().headers()['x-auth-token'] });
      let body = { teams: [] };
      if (u.pathname === '/v4/competitions/PD/teams') body = { teams: [BAR, RMA] };
      if (u.pathname === '/v4/teams/81/matches' && u.searchParams.get('status').startsWith('SCHEDULED'))
        // the whole rest of the season, newest first (as the site may give it): the page takes the nearest five
        body = { matches: [m(8, OPP(7), BAR, 200, 'SCHEDULED'), m(7, BAR, OPP(6), 190, 'SCHEDULED'), m(6, OPP(5), BAR, 180, 'SCHEDULED'),
                           m(5, BAR, OPP(4), 20, 'SCHEDULED'), m(4, OPP(3), BAR, 13, 'TIMED'), m(3, BAR, OPP(2), 9, 'TIMED'),
                           m(2, OPP(1), BAR, 6, 'TIMED'), m(1, BAR, RMA, 2, 'TIMED')] };
      if (u.pathname === '/v4/teams/81/matches' && u.searchParams.get('status') === 'FINISHED' && !u.searchParams.has('dateFrom'))
        body = { matches: [m(0, BAR, OPP(9), -0.3, 'FINISHED', [3, 1])] };
      return r.fulfill({ headers: cors, contentType: 'application/json', body: JSON.stringify(body) });
    }
    if (u.host === 'www.thesportsdb.com') tsdb.push(u.pathname);
    return r.abort();
  });
  const p = await ctx.newPage();
  const errors = [];
  p.on('pageerror', e => errors.push(e.message));
  await p.goto(page('tv.html') + '#teams=133739&panel=matches&fdkey=abc123');
  await p.evaluate(() => { FD_PAUSE = 0; });
  await p.waitForFunction(() => (NEXT_LIST['133739'] || []).length === 5, null, { timeout: 8000 }).catch(() => errors.push('no fd games'));
  await p.evaluate(() => renderPanel(false));
  const r = await p.evaluate(() => ({ rows: [...document.querySelectorAll('.week li')].map(x => x.textContent),
                                      hd: document.getElementById('week-hd').textContent }));
  check('football-data: each game with its date; a game whose time is not set says so', r.rows.length === 5
        && /أكتوبر|نوفمبر|ديسمبر|يناير/.test(r.rows[2]) && r.rows[4].includes('الوقت لم يُحدد') && !r.rows[3].includes('الوقت لم يُحدد'), JSON.stringify(r.rows));
  check('football-data: the next five games, Arabic names', !errors.length && r.rows.length === 5
        && r.rows[0].includes('برشلونة') && r.rows[0].includes('ريال مدريد') && !r.rows.some(x => x.includes('٣ - ١')) && r.hd === 'مباريات فرقك القادمة', JSON.stringify({ r, errors }));
  const ids = await p.evaluate(() => (NEXT_LIST['133739'] || []).map(e => e.idEvent).join(','));
  check('football-data: the nearest five of the season, not the last five', ids === 'fd1,fd2,fd3,fd4,fd5', ids);
  check('football-data: no limit asked (the site counted it from the season\'s end)', !calls.some(c => c.path.includes('limit=5')), JSON.stringify(calls.map(c => c.path)));
  check('football-data: the key goes in the X-Auth-Token header, TheSportsDB not asked for that club',
        calls.length && calls.every(c => c.key === 'abc123') && !calls.some(c => c.path.includes('abc123'))
        && !tsdb.some(x => /eventsnext|eventslast/.test(x)), JSON.stringify({ calls: calls.slice(0, 3), tsdb }));
  await ctx.close();
}

// ---- football-data refuses the filtered request (400): the season's list without filters, sorted out on the page
{
  const ctx = await browser.newContext({ viewport: { width: 1920, height: 1080 } });
  const day = n => new Date(Date.now() + n * 864e5).toISOString();
  const BAR = { id: 81, name: 'FC Barcelona', shortName: 'Barça' }, O = n => ({ id: 900 + n, name: 'Opp ' + n, shortName: 'Opp ' + n });
  const m = (id, h, a, n, st, sc) => ({ id, utcDate: day(n), status: st, homeTeam: h, awayTeam: a,
                                        score: { fullTime: sc ? { home: sc[0], away: sc[1] } : { home: null, away: null } } });
  await ctx.route(/^https?:/, r => {
    const u = new URL(r.request().url()), cors = { 'Access-Control-Allow-Origin': '*' };
    if (u.host !== 'api.football-data.org') return r.abort();
    if (u.pathname === '/v4/competitions/PD/teams') return r.fulfill({ headers: cors, contentType: 'application/json', body: JSON.stringify({ teams: [BAR] }) });
    if (u.pathname === '/v4/teams/81/matches' && u.searchParams.has('status'))
      return r.fulfill({ status: 400, headers: cors, contentType: 'application/json', body: '{"error":"Invalid filter"}' });
    if (u.pathname === '/v4/teams/81/matches')   // the whole season, in no order: past, future, a result yesterday
      return r.fulfill({ headers: cors, contentType: 'application/json', body: JSON.stringify({ matches: [
        m(9, BAR, O(9), 30, 'SCHEDULED'), m(1, O(1), BAR, -20, 'FINISHED', [0, 2]), m(2, BAR, O(2), 2, 'TIMED'),
        m(3, BAR, O(3), -1, 'FINISHED', [4, 0]), m(4, O(4), BAR, 6, 'TIMED'), m(5, BAR, O(5), 9, 'TIMED'),
        m(6, O(6), BAR, 13, 'SCHEDULED'), m(7, BAR, O(7), 20, 'SCHEDULED'), m(8, O(8), BAR, 27, 'SCHEDULED')] }) });
    return r.fulfill({ headers: cors, contentType: 'application/json', body: '{"teams":[]}' });
  });
  const p = await ctx.newPage();
  await p.goto(page('tv.html') + '#teams=133739&panel=matches&fdkey=abc123');
  await p.evaluate(() => { FD_PAUSE = 0; });
  await p.waitForFunction(() => (NEXT_LIST['133739'] || []).length === 5, null, { timeout: 8000 }).catch(() => {});
  const r = await p.evaluate(() => ({ next: (NEXT_LIST['133739'] || []).map(e => e.idEvent), results: (RESULTS['133739'] || []).map(e => e.idEvent) }));
  check('football-data refuses the filter (400): the five coming games from the season list, in order, and yesterday\'s result',
        r.next.join(',') === 'fd2,fd4,fd5,fd6,fd7' && r.results.join(',') === 'fd3', JSON.stringify(r));
  await ctx.close();
}

// ---- football-data refuses a refresh (over the minute's limit, «token invalid»): its games stay, no TheSportsDB
{
  const ctx = await browser.newContext({ viewport: { width: 1920, height: 1080 } });
  const day = n => new Date(Date.now() + n * 864e5).toISOString();
  const BAR = { id: 81, name: 'FC Barcelona', shortName: 'Barça' }, O = n => ({ id: 900 + n, name: 'Opp ' + n, shortName: 'Opp ' + n });
  const m = (id, n) => ({ id, utcDate: day(n), status: 'TIMED', homeTeam: BAR, awayTeam: O(id), score: { fullTime: { home: null, away: null } } });
  let refuse = false; const tsdb = [];
  await ctx.route(/^https?:/, r => {
    const u = new URL(r.request().url()), cors = { 'Access-Control-Allow-Origin': '*' };
    if (u.host === 'www.thesportsdb.com') { tsdb.push(u.pathname); return r.abort(); }
    if (u.host !== 'api.football-data.org') return r.abort();
    if (refuse) return r.fulfill({ status: 400, headers: cors, contentType: 'application/json', body: '{"error":"Your API token is invalid."}' });
    if (u.pathname === '/v4/competitions/PD/teams') return r.fulfill({ headers: cors, contentType: 'application/json', body: JSON.stringify({ teams: [BAR] }) });
    if (u.pathname === '/v4/teams/81/matches') return r.fulfill({ headers: cors, contentType: 'application/json', body: JSON.stringify({ matches: [1,2,3,4,5,6].map(i => m(i, i * 3)) }) });
    return r.fulfill({ headers: cors, contentType: 'application/json', body: '{"teams":[]}' });
  });
  const p = await ctx.newPage();
  await p.goto(page('tv.html') + '#teams=133739&panel=matches&fdkey=abc123');
  await p.evaluate(() => { FD_PAUSE = 0; });
  await p.waitForFunction(() => (NEXT_LIST['133739'] || []).length === 5, null, { timeout: 8000 }).catch(() => {});
  refuse = true; tsdb.length = 0;
  await p.evaluate(() => loadMatches());
  await p.waitForTimeout(800);
  const ids = await p.evaluate(() => (NEXT_LIST['133739'] || []).map(e => e.idEvent).join(','));
  check('football-data refuses a refresh: its five games stay, TheSportsDB not asked', ids === 'fd1,fd2,fd3,fd4,fd5'
        && !tsdb.some(x => /eventsnext|eventslast/.test(x)), JSON.stringify({ ids, tsdb }));
  await ctx.close();
}

// ---- a league refused while the list of clubs is made: that list is not kept a month (Barcelona left out)
{
  const ctx = await browser.newContext({ viewport: { width: 1920, height: 1080 } });
  await ctx.route(/^https?:/, r => {
    const u = new URL(r.request().url()), cors = { 'Access-Control-Allow-Origin': '*' };
    if (u.host !== 'api.football-data.org') return r.abort();
    if (u.pathname === '/v4/competitions/PD/teams') return r.fulfill({ status: 400, headers: cors, contentType: 'application/json', body: '{"message":"Your API token is invalid."}' });
    if (u.pathname === '/v4/competitions/PL/teams') return r.fulfill({ headers: cors, contentType: 'application/json', body: JSON.stringify({ teams: [{ id: 57, name: 'Arsenal FC', shortName: 'Arsenal' }] }) });
    return r.fulfill({ headers: cors, contentType: 'application/json', body: '{"teams":[],"matches":[]}' });
  });
  const p = await ctx.newPage();
  await p.goto(page('tv.html') + '#teams=133739&panel=matches&fdkey=abc123');
  await p.evaluate(() => { FD_PAUSE = 0; });
  const r = await p.evaluate(async () => { FD_INDEX = null; await fdIndex();
    return { kept: localStorage.getItem('fdIndex2'), soon: FD_INDEX && Date.now() - FD_INDEX.at > 29 * 86400000 }; });
  check('a league refused: the list of clubs is not kept, asked again soon', r.kept === null && r.soon, JSON.stringify(r));
  await ctx.close();
}

// ---- only the home screen asks football-data: it shares its games, the screensaver shows them with no request
{
  const ctx = await browser.newContext({ viewport: { width: 1920, height: 1080 } });
  const day = n => new Date(Date.now() + n * 864e5).toISOString();
  const ev = i => ({ idEvent: 'fd' + i, fdId: i, strTimestamp: day(i), strHomeTeam: 'Barça', idHomeTeam: '133739',
                     strAwayTeam: 'Opp ' + i, idAwayTeam: null, intHomeScore: null, intAwayScore: null, strStatus: 'NS', strProgress: '', timeTbc: false });
  const fd = [];
  await ctx.route(/^https?:/, r => { if (new URL(r.request().url()).host === 'api.football-data.org') fd.push(r.request().url()); return r.abort(); });
  // the screensaver: the home screen's list through window.UrTVShared
  await ctx.addInitScript(json => { window.UrTVShared = { get: () => json }; },
    JSON.stringify({ at: Date.now(), teams: { '133739': { next: [1,2,3,4,5].map(ev), match: ev(1), results: [] } } }));
  const p = await ctx.newPage();
  await p.goto(page('tv.html') + '#teams=133739&panel=matches&fdkey=abc123&fdnative=1');
  await p.waitForFunction(() => (NEXT_LIST['133739'] || []).length === 5, null, { timeout: 8000 }).catch(() => {});
  await p.waitForTimeout(500);
  const ids = await p.evaluate(() => (NEXT_LIST['133739'] || []).map(e => e.idEvent).join(','));
  check('the screensaver: the home screen\'s five games, no football-data request of its own', ids === 'fd1,fd2,fd3,fd4,fd5' && fd.length === 0, JSON.stringify({ ids, fd }));
  await ctx.close();

  // the home screen: asks, then shares
  const ctx2 = await browser.newContext({ viewport: { width: 1920, height: 1080 } });
  const BAR = { id: 81, name: 'FC Barcelona', shortName: 'Barça' };
  await ctx2.route(/^https?:/, r => {
    const u = new URL(r.request().url()), cors = { 'Access-Control-Allow-Origin': '*' };
    if (u.host !== 'api.football-data.org') return r.abort();
    if (u.pathname === '/v4/competitions/PD/teams') return r.fulfill({ headers: cors, contentType: 'application/json', body: JSON.stringify({ teams: [BAR] }) });
    if (u.pathname === '/v4/teams/81/matches') return r.fulfill({ headers: cors, contentType: 'application/json', body: JSON.stringify({ matches: [1,2,3,4,5,6].map(i =>
      ({ id: i, utcDate: day(i), status: 'TIMED', homeTeam: BAR, awayTeam: { id: 900 + i, name: 'Opp ' + i }, score: { fullTime: { home: null, away: null } } })) }) });
    return r.fulfill({ headers: cors, contentType: 'application/json', body: '{"teams":[]}' });
  });
  await ctx2.addInitScript(() => { window.__shared = []; window.UrTVShare = { share: j => window.__shared.push(j) }; });
  const p2 = await ctx2.newPage();
  await p2.goto(page('tv.html') + '#teams=133739&panel=matches&fdkey=abc123&fdnative=1');
  await p2.waitForFunction(() => window.__shared.length > 0, null, { timeout: 8000 }).catch(() => {});
  const got = await p2.evaluate(() => { const j = JSON.parse(window.__shared.at(-1) || '{}'); return ((j.teams || {})['133739'] || {}).next || []; });
  check('the home screen shares its five football-data games', got.length === 5 && got[0].idEvent === 'fd1', JSON.stringify(got.map(e => e.idEvent)));
  await ctx2.close();
}

// ---- morning and evening adhkar: they take the card in their hours, whatever card is picked, and give it back
{
  const ctx = await browser.newContext({ viewport: { width: 1920, height: 1080 } });
  await ctx.route(/^https?:/, r => r.abort());
  const at = async (p, h, mi) => p.evaluate(([h, mi]) => {
    const real = riyadhParts;
    riyadhParts = () => Object.assign(real(), { h, mi, s: 0 });
    buildPrayers({ Fajr: '06:10', Sunrise: '07:40', Dhuhr: '12:40', Asr: '15:10', Maghrib: '17:30', Isha: '19:00' });
    tickAdhkar();
    const w = document.querySelector('.week');
    return { panel: PANEL, hd: document.getElementById('week-hd').textContent, shown: w.style.display !== 'none' };
  }, [h, mi]);
  const p = await ctx.newPage();
  await p.goto(page('tv.html') + '#panel=forecast&adhkar=1');
  await p.waitForTimeout(300);
  const early = await at(p, 6, 0), morning = await at(p, 6, 15);
  await p.waitForTimeout(450);
  const rows = await p.evaluate(() => [...document.querySelectorAll('.week li')].map(x => x.textContent));
  const hd = await p.evaluate(() => document.getElementById('week-hd').textContent);
  const later = await at(p, 9, 0), evening = await at(p, 21, 5);
  await p.waitForTimeout(450);
  const ehd = await p.evaluate(() => document.getElementById('week-hd').textContent);
  check('adhkar: not before Fajr, morning from Fajr, the picked card back at 9, evening at 21',
        early.panel === 'forecast' && morning.panel === 'adhkar' && hd === 'أذكار الصباح' && later.panel === 'forecast'
        && evening.panel === 'adhkar' && ehd === 'أذكار المساء', JSON.stringify({ early, morning, hd, later, evening, ehd }));
  check('adhkar: Ayat al-Kursi first, fetched text, with its count', rows.length === 2 && rows[0].replace(/[\u064B-\u0652\u0670]/g, '').includes('الحي القيوم')
        && rows[1].includes('آية الكرسي') && rows[1].includes('مرة واحدة'), JSON.stringify(rows));
  // next dhikr after its time
  const next = await p.evaluate(() => { ADHKAR_AT = 0; tickAdhkar(); return ADHKAR_I; });
  check('adhkar: the next one in turn after its time', next === 1, String(next));
  await ctx.close();

  const ctx2 = await browser.newContext({ viewport: { width: 1920, height: 1080 } });
  await ctx2.route(/^https?:/, r => r.abort());
  const p2 = await ctx2.newPage();
  await p2.goto(page('tv.html') + '#panel=none&adhkar=1');
  await p2.waitForTimeout(300);
  const nOn = await at(p2, 22, 0), nOff = await at(p2, 1, 0);
  const p3 = await ctx2.newPage();
  await p3.goto(page('tv.html') + '#panel=forecast&adhkar=0');
  await p3.waitForTimeout(300);
  const off = await at(p3, 7, 0);
  check('adhkar: shown even with no card (then hidden again); not at all when turned off',
        nOn.panel === 'adhkar' && nOn.shown && !nOff.shown && off.panel === 'forecast', JSON.stringify({ nOn, nOff, off }));
  await ctx2.close();
}

// ---- no team picked: the matches place stays empty, and the card in turn never shows the matches
{
  const ctx = await browser.newContext({ viewport: { width: 1920, height: 1080 } });
  await ctx.route(/^https?:/, r => r.abort());
  const p = await ctx.newPage();
  await p.goto(page('tv.html') + '#teams=&panel=auto');
  await p.waitForTimeout(600);
  const r = await p.evaluate(() => ({ vis: getComputedStyle(document.querySelector('.p-match')).visibility,
    text: document.getElementById('match-row').textContent.trim(), turn: hasCard('matches') }));
  check('no team: the matches place empty, never the matches card in turn', r.vis === 'hidden' && r.text === '' && !r.turn, JSON.stringify(r));
  await ctx.close();
}

// ---- in the app (#fdnative=1) the page sends no key at all: the app adds it (FdProxy), so no CORS preflight
{
  const ctx = await browser.newContext({ viewport: { width: 1920, height: 1080 } });
  const seen = [];
  await ctx.route(/^https?:/, r => {
    const u = new URL(r.request().url());
    if (u.host === 'api.football-data.org') {
      seen.push({ method: r.request().method(), key: r.request().headers()['x-auth-token'] || null });
      return r.fulfill({ headers: { 'Access-Control-Allow-Origin': '*' }, contentType: 'application/json', body: '{"teams":[],"matches":[]}' });
    }
    return r.abort();
  });
  const p = await ctx.newPage();
  await p.goto(page('tv.html') + '#teams=133739&panel=matches&fdkey=abc123&fdnative=1');
  await p.waitForTimeout(1500);
  check('in the app: plain GETs to football-data, no key header from the page', seen.length > 0
        && seen.every(x => x.method === 'GET' && x.key === null), JSON.stringify(seen.slice(0, 3)));
  await ctx.close();
}

// ---- the TV-shows card was removed: an old #panel=shows gets the cards in turn, and nothing calls TVMaze
{
  const ctx = await browser.newContext({ viewport: { width: 1920, height: 1080 } });
  const seen = [];
  await ctx.route(/^https?:/, r => { seen.push(new URL(r.request().url()).host); return r.abort(); });
  const p = await ctx.newPage();
  await p.goto(page('tv.html') + '#panel=shows&genres=Drama&shows=1&minrating=7');
  await p.waitForTimeout(1200);
  const r = await p.evaluate(() => ({ panel: CONFIG.panel, hd: document.getElementById('week-hd').textContent }));
  check('old #panel=shows: the cards in turn instead (verse first), no TVMaze', r.panel === 'auto' && ['آية اليوم', 'حديث اليوم'].includes(r.hd)
        && !seen.some(h => /tvmaze/.test(h)), JSON.stringify(r) + ' ' + seen.join(','));
  await ctx.close();
}

// ---- offline start: weather and the match row stay as they were last time
{
  const ctx = await browser.newContext({ viewport: { width: 1920, height: 1080 } });
  await ctx.route(/^https?:/, r => r.abort());
  const p = await ctx.newPage();
  await p.goto(page('tv.html') + '#teams=136013');
  await p.waitForTimeout(800);
  await p.evaluate(() => {
    document.getElementById('wx-temp').textContent = '٣٨°';
    document.getElementById('wx-desc').textContent = 'غبار';
    document.getElementById('wx-desc').classList.add('dust');
    document.getElementById('match-row').innerHTML = '<div class="saved-match">SAVED</div>';
    saveSnap();
  });
  await p.reload();
  await p.waitForTimeout(1500);
  const r = await p.evaluate(() => ({
    temp: document.getElementById('wx-temp').textContent,
    dust: document.getElementById('wx-desc').classList.contains('dust'),
    row: document.getElementById('match-row').textContent.trim() }));
  check('offline start: weather and match row kept', r.temp === '٣٨°' && r.dust && r.row === 'SAVED', JSON.stringify(r));
  await ctx.close();
}

// ---- a finished game's score stays up for a day, even when the API has already moved on
{
  const ctx = await browser.newContext({ viewport: { width: 1920, height: 1080 } });
  await ctx.route(/^https?:/, r => r.abort());
  const p = await ctx.newPage();
  await p.goto(page('tv.html') + '#teams=136013&panel=matches');
  await p.waitForTimeout(800);
  const slot = () => p.evaluate(() => document.getElementById('match-row').innerText.replace(/\s+/g, ' ').trim());
  const game = (id, hoursAgo, score, status) => `({idEvent:'${id}', strHomeTeam:'Al-Hilal', strAwayTeam:'Al-Nassr',
    strTimestamp:new Date(Date.now() - ${hoursAgo} * 36e5).toISOString(), intHomeScore:${score ? score[0] : null},
    intAwayScore:${score ? score[1] : null}, strStatus:'${status}'})`;

  await p.evaluate(`MATCHES['136013'] = ${game('g1', 1.8, [2, 1], 'FT')}; renderMatches();`);
  let t = await slot();
  check('whistle blown: final score at once, not «مباشر»', t.includes('النتيجة النهائية') && t.includes('٢ - ١') && !t.includes('مباشر'), t);

  await p.evaluate(`keepResult('136013', MATCHES['136013']); MATCHES['136013'] = ${game('g2', -72, null, 'NS')};
                    RESULTS = {}; renderMatches();`);
  t = await slot();
  check('API moved to the next fixture: score still shown', t.includes('النتيجة النهائية') && t.includes('٢ - ١'), t);

  await p.reload();
  await p.waitForTimeout(800);
  await p.evaluate(`MATCHES['136013'] = ${game('g2', -72, null, 'NS')}; renderMatches();`);
  t = await slot();
  check('after a restart the same day: score still shown', t.includes('٢ - ١'), t);

  await p.evaluate(`KEPT['136013'] = [${game('g1', 27, [2, 1], 'FT')}]; renderMatches();`);
  t = await slot();
  check('a day later: score gone, next fixture shown', !t.includes('٢ - ١') && t.includes('×'), t);
  await ctx.close();
}

// ---- automatic location + the mosque's Mawaqit timetable (adhan, iqama, Jumu'a), then offline from the cache
{
  const ctx = await browser.newContext({ viewport: { width: 1920, height: 1080 } });
  // a whole year where every day has the same times: 05:10 / 07:00 / 12:30 / 15:40 / 18:20 / 20:05
  const day = ['05:10', '07:00', '12:30', '15:40', '18:20', '20:05'];
  const month = Object.fromEntries(Array.from({ length: 31 }, (_, i) => [String(i + 1), day]));
  const iqMonth = Object.fromEntries(Array.from({ length: 31 }, (_, i) => [String(i + 1), ['+15', '13:00', '+5', '18:30', '+12']]));
  const conf = { name: 'Masjid Test  مسجد التجربة - TOWN', timezone: 'Europe/Berlin', iqamaEnabled: true,
                 jumua: '13:15', jumuaAsDuhr: false, calendar: Array(12).fill(month), iqamaCalendar: Array(12).fill(iqMonth) };
  const html = `<html><script>\n    let confData = ${JSON.stringify(conf)};\n    let x = 1;\n</script></html>`;
  let online = true;
  const hits = {};
  await ctx.route(/^https?:/, r => {
    const u = r.request().url(), cors = { 'Access-Control-Allow-Origin': '*' };
    const host = new URL(u).host;
    hits[host] = (hits[host] || 0) + 1;
    if (!online) return r.abort();
    if (host === 'get.geojs.io') return r.fulfill({ headers: cors, contentType: 'application/json',
      body: JSON.stringify({ city: 'Munich', latitude: '48.1374', longitude: '11.5755', timezone: 'Europe/Berlin',
                             country_code: 'DE', country: 'Germany' }) });
    if (host === 'geocoding-api.open-meteo.com') {
      const lang = new URL(u).searchParams.get('language');
      return r.fulfill({ headers: cors, contentType: 'application/json',
        body: JSON.stringify({ results: [{ name: lang === 'de' ? 'München' : lang === 'ar' ? 'ميونخ' : 'Munich', latitude: 48.137, longitude: 11.575 }] }) });
    }
    if (host === 'mawaqit.net') return r.fulfill({ headers: cors, contentType: 'text/html', body: html });
    return r.abort();
  });
  const p = await ctx.newPage();
  const errors = [];
  p.on('pageerror', e => errors.push(e.message));
  await p.goto(page('tv.html') + '#city=الرياض&lat=24.7&lon=46.7&tz=Asia/Riyadh&loc=auto&mosque=test-mosque&panel=prayers');
  await p.waitForFunction(() => PRAYERS.length && GEO, null, { timeout: 6000 }).catch(() => errors.push('no prayers/location'));
  await p.waitForTimeout(500);
  const read = () => p.evaluate(() => ({
    tz: CONFIG.timezone, city: CONFIG.city, lat: CONFIG.latitude, sub: document.getElementById('clock-sub').textContent,
    times: PRAYERS.map(x => x.key + '=' + String(x.h).padStart(2, '0') + ':' + String(x.mi).padStart(2, '0')).join(' '),
    iqama: JSON.stringify(CONFIG.iqama), friday: isFriday(), method: CONFIG.prayerMethod,
    panel: document.querySelector('.week').innerText }));
  let r = await read();
  check('auto location: no script errors', !errors.length, errors.join('; '));
  check('auto location abroad: time zone, city and country in the local language («München, Deutschland»)',
        r.tz === 'Europe/Berlin' && r.city === 'München, Deutschland' && r.sub === 'بتوقيت München' && Math.abs(r.lat - 48.1374) < 1e-6,
        JSON.stringify(r));
  const dhuhr = r.friday ? '13:15' : '12:30';
  check('mosque: adhan times from the Mawaqit timetable (Jumu\'a on Friday)',
        r.times === `Fajr=05:10 Dhuhr=${dhuhr} Asr=15:40 Maghrib=18:20 Isha=20:05`, r.times);
  check('mosque: iqama from "+N" and from "HH:MM"',
        r.iqama === JSON.stringify({ Fajr: 15, Dhuhr: 30, Asr: 5, Maghrib: 10, Isha: 12 }), r.iqama);
  check('fallback method outside Saudi Arabia is MWL', r.method === 3, r.method);
  check('prayers card shows the mosque times', r.panel.includes('٥:١٠'), r.panel);

  online = false;
  await p.reload();
  await p.waitForFunction(() => PRAYERS.length, null, { timeout: 6000 }).catch(() => errors.push('no prayers offline'));
  r = await read();
  check('offline: the cached place and the cached mosque timetable are used',
        r.tz === 'Europe/Berlin' && r.city === 'München, Deutschland' && r.times.startsWith('Fajr=05:10'), JSON.stringify(r));
  await ctx.close();
}

// ---- auto location in an Arab country: the Arabic name, no country
{
  const ctx = await browser.newContext({ viewport: { width: 1920, height: 1080 } });
  await ctx.route(/^https?:/, r => {
    const u = r.request().url(), cors = { 'Access-Control-Allow-Origin': '*' }, host = new URL(u).host;
    if (host === 'get.geojs.io') return r.fulfill({ headers: cors, contentType: 'application/json',
      body: JSON.stringify({ city: 'Jeddah', latitude: '21.54', longitude: '39.17', timezone: 'Asia/Riyadh', country_code: 'SA', country: 'Saudi Arabia' }) });
    if (host === 'geocoding-api.open-meteo.com') return r.fulfill({ headers: cors, contentType: 'application/json',
      body: JSON.stringify({ results: [{ name: new URL(u).searchParams.get('language') === 'ar' ? 'جدة' : 'Jeddah', latitude: 21.54, longitude: 39.17 }] }) });
    return r.abort();
  });
  const p = await ctx.newPage();
  await p.goto(page('tv.html') + '#city=الرياض&lat=24.7&lon=46.7&tz=Asia/Riyadh&loc=auto');
  await p.waitForFunction(() => GEO, null, { timeout: 6000 }).catch(() => {});
  const r = await p.evaluate(() => ({ city: CONFIG.city, sub: document.getElementById('clock-sub').textContent }));
  check('auto location in Saudi Arabia: Arabic name («جدة»)', r.city === 'جدة' && r.sub === 'بتوقيت جدة', JSON.stringify(r));
  await ctx.close();
}

// ---- no #loc / #mosque: the page behaves as before (fixed city, calculated times, never calls Mawaqit or IP lookups)
{
  const ctx = await browser.newContext({ viewport: { width: 1920, height: 1080 } });
  const seen = [];
  await ctx.route(/^https?:/, r => { seen.push(new URL(r.request().url()).host); return r.abort(); });
  const p = await ctx.newPage();
  await p.goto(page('tv.html') + '#city=جدة&lat=21.5433&lon=39.1728&tz=Asia/Riyadh&panel=prayers');
  await p.waitForFunction(() => PRAYERS.length, null, { timeout: 6000 }).catch(() => {});
  const r = await p.evaluate(() => ({ tz: CONFIG.timezone, city: CONFIG.city, n: PRAYERS.length, method: CONFIG.prayerMethod }));
  check('fixed city: unchanged, calculated times, Umm al-Qura', r.tz === 'Asia/Riyadh' && r.city === 'جدة' && r.n === 5 && r.method === 4, JSON.stringify(r));
  check('fixed city: no Mawaqit or IP lookups', !seen.some(h => /mawaqit|geojs|ipwho|ipapi/.test(h)), seen.join(','));
  await ctx.close();
}

// ---- a website as an app on the TV: the remote's arrows move between links, fields and buttons; OK clicks
{
  const java = readFileSync(path.join(ROOT, 'android/launcher/src/main/java/sa/urtv/home/WebAppActivity.java'), 'utf8');
  let block = java.slice(java.indexOf('static final String NAV ='));
  block = block.slice(0, block.indexOf('";\n}') + 2).replace(/^\s*\/\/.*$/mg, '');
  const nav = [...block.matchAll(/"((?:[^"\\]|\\.)*)"/g)].map(m => m[1].replace(/\\"/g, '"').replace(/\\\\/g, '\\')).join('');
  const ctx = await browser.newContext({ viewport: { width: 1280, height: 720 } });
  const p = await ctx.newPage();
  const errors = [];
  p.on('pageerror', e => errors.push(e.message));
  await p.setContent(`<html dir="rtl"><body style="font:20px sans-serif">
    <div><a href="#a" id="a1">دخول</a> <a href="#b" id="a2" style="margin-right:200px">تسجيل</a></div>
    <div style="margin-top:80px"><input id="user"></div>
    <div style="margin-top:80px"><button id="btn" onclick="this.textContent='clicked'">إرسال</button></div></body></html>`);
  await p.evaluate(nav); await p.evaluate(nav);
  await p.waitForTimeout(400);
  const at = () => p.evaluate(() => (document.querySelector('.urtv-f') || {}).id);
  const seq = [await at()];
  for (const k of ['ArrowLeft', 'ArrowDown', 'ArrowDown']) { await p.keyboard.press(k); seq.push(await at()); }
  await p.keyboard.press('Enter');
  const clicked = await p.evaluate(() => document.getElementById('btn').textContent);
  await p.keyboard.press('ArrowUp'); seq.push(await at());
  check('web app on the TV: arrows move link → link → field → button → back up to the field, OK clicks',
        !errors.length && seq.join(',') === 'a1,a2,user,btn,user' && clicked === 'clicked', JSON.stringify({ seq, clicked, errors }));
  await ctx.close();
}

await browser.close();
console.log(failed ? `\n${failed} failed` : '\nall page tests passed');
process.exit(failed ? 1 : 0);
