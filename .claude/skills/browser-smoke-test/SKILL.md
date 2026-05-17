---
name: browser-smoke-test
description: Smoke-test Golova UI changes in a real browser using headless Chrome + puppeteer-core. Use this whenever you change ui.cljs / style.css / state.cljs — the unit-test alternative is "compile clean" which proves nothing about whether the page renders.
---

# Browser smoke-test for Golova

The compile check (`shadow-cljs watch`) catches syntax and undeclared vars
but tells you nothing about whether the page renders correctly or whether a
button does the right thing. Run actual browser tests instead.

## Setup (one-time)

A puppeteer harness already exists at `/tmp/puppet-test/`. If it doesn't:

```bash
mkdir -p /tmp/puppet-test && cd /tmp/puppet-test
echo '{"type": "module"}' > package.json
npm install --silent puppeteer-core
```

Chrome is at `/Applications/Google Chrome.app/Contents/MacOS/Google Chrome` on
this Mac. Use puppeteer-core (not puppeteer) so we don't redownload Chromium.

## Dev server

Shadow-cljs serves at **port 8088** (not 8089 — the README is wrong). Start
the watch in background:

```bash
npx shadow-cljs watch app
```

Wait for `Build completed` before screenshotting. After file edits, wait
again. Use this poll pattern (does not burn cache):

```bash
until tail -2 /private/tmp/claude-*/tasks/<task-id>.output | grep -q "Build completed"; do sleep 0.5; done
```

## Test template

The fastest way to load realistic data is to inject the EDN example directly
into `localStorage` before reload — bypasses the file-picker.

```js
// /tmp/puppet-test/<name>.mjs
import puppeteer from 'puppeteer-core';
import fs from 'fs';

const edn = fs.readFileSync(
  '/Users/supernaturval/Documents/GitHub/golova-clj/examples/personal-kb.edn',
  'utf8');

const browser = await puppeteer.launch({
  executablePath: '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
  headless: 'new', args: ['--no-sandbox']
});
const page = await browser.newPage();
await page.setViewport({width: 1400, height: 900});

// Capture errors — silence here is success.
const errs = [];
page.on('pageerror', e => errs.push('PE: ' + e.message.slice(0, 300)));
page.on('console', m => {
  if (m.type() === 'error') errs.push('CON: ' + m.text().slice(0, 300));
});

await page.goto('http://localhost:8088/index.html', {waitUntil: 'networkidle0'});
await page.evaluate((s) => localStorage.setItem('golova.state.v1', s), edn);
await page.reload({waitUntil: 'networkidle0'});
await new Promise(r => setTimeout(r, 1200));

// ... interact + screenshot ...

await page.screenshot({path: '/tmp/golova-shots/<name>.png'});
console.log('errs:'); for (const e of errs) console.log(e);
await browser.close();
```

Then `Read` the screenshot to actually see what rendered. Don't skip this.

## Navigation tricks

### Switch domain (more reliable than clicking sidebar)

The `:current-domain` key controls which DB the views read from. Easiest way
to start in a non-default domain: mutate the EDN before injecting.

```js
let edn = fs.readFileSync('.../personal-kb.edn', 'utf8');
edn = edn.replace(/:current-domain :people/, ':current-domain :books');
```

### Open via Cmd-K palette

```js
await page.keyboard.down('Meta'); await page.keyboard.press('k'); await page.keyboard.up('Meta');
await new Promise(r => setTimeout(r, 200));
await page.keyboard.type('alice');
await new Promise(r => setTimeout(r, 200));
// Click the first item with matching sublabel
await page.evaluate(() => {
  const t = Array.from(document.querySelectorAll('.palette-item'))
    .find(i => i.querySelector('.sub')?.textContent === 'entity');
  if (t) t.click();
});
```

### Set a controlled-input value (React-safe)

The topbar / palette / textareas are React-controlled. `inp.value = 'x'`
won't fire onChange. Use the prototype's native setter + dispatch event:

```js
await page.evaluate(() => {
  const inp = document.querySelector('.topbar input.query');
  const setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
  setter.call(inp, 'priority-to-read(?b)');
  inp.dispatchEvent(new Event('input', {bubbles: true}));
});
```

### Known puppeteer typing flake

`page.keyboard.type('alice')` often eats the first character, yielding
"lice" in the field. Workaround: type one char at a time with a tiny pause,
or use the React-safe setter above when you need exact text.

## Inspecting state without clicking

Dump computed positions / sizes / DOM directly — much faster than scrolling
through screenshots to find a layout bug.

```js
const info = await page.evaluate(() => {
  const els = Array.from(document.querySelectorAll('aside .subsection'));
  return els.map(e => {
    const r = e.getBoundingClientRect();
    return {text: e.textContent.trim().slice(0, 20),
            left: Math.round(r.left),
            padLeft: getComputedStyle(e).paddingLeft};
  });
});
console.log(JSON.stringify(info, null, 2));
```

This is how the global `.empty { padding: 60px 20px }` rule was found to be
clobbering `.subsection.empty`'s padding — visual inspection couldn't tell.

## What to test after a UI change

Minimum: load the EDN, screenshot the Home page, screenshot the affected
view, check `errs` is empty.

For interactive features (chip editor, table toolbar, query view, palette),
script the interaction and verify the resulting DOM / screenshot.

Always verify before reporting "done". Type-checking is not feature-checking.
