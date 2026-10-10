// 移动端适配 + 新功能 的真实渲染校验
// 用法: node verify-mobile.js [baseUrl]
const puppeteer = require('puppeteer');
const fs = require('fs');

const BASE = process.argv[2] || 'http://127.0.0.1';
const OUT = '/www/wwwroot/WX-chat-bot/pptr/mobile-check';
fs.mkdirSync(OUT, { recursive: true });

const ROUTES = [
  ['status', '/status'],
  ['search', '/search'],
  ['jobs', '/jobs'],
  ['history', '/history'],
  ['library', '/library'],
  ['config', '/config'],
];

const VIEWPORTS = [
  ['mobile', 390, 844, 3],   // iPhone 14 Pro
  ['tablet', 820, 1180, 2],  // iPad Air
  ['desktop', 1440, 900, 1],
];

(async () => {
  const browser = await puppeteer.launch({
    headless: 'new',
    args: [
      '--no-sandbox',
      '--disable-setuid-sandbox',
      '--disable-dev-shm-usage',
      '--font-render-hinting=none',
      '--lang=zh-CN',
    ],
  });

  const summary = [];

  for (const [vpName, w, h, dpr] of VIEWPORTS) {
    for (const [name, path] of ROUTES) {
      const page = await browser.newPage();
      await page.setViewport({ width: w, height: h, deviceScaleFactor: dpr });

      const errors = [];
      page.on('pageerror', (e) => errors.push('pageerror: ' + e.message));
      page.on('console', (m) => {
        if (m.type() === 'error') errors.push('console: ' + m.text().slice(0, 160));
      });
      page.on('requestfailed', (r) =>
        errors.push('reqfail: ' + r.url().replace(BASE, '') + ' ' + (r.failure()?.errorText || '')),
      );

      try {
        await page.goto(BASE + path, { waitUntil: 'networkidle2', timeout: 45000 });
      } catch (e) {
        errors.push('goto: ' + e.message);
      }
      await new Promise((r) => setTimeout(r, 900));

      // 检查横向溢出（移动端最常见的布局问题）
      const overflow = await page.evaluate(() => {
        const de = document.documentElement;
        const over = de.scrollWidth - de.clientWidth;
        // 找出真正超出视口的元素
        const bad = [];
        if (over > 1) {
          document.querySelectorAll('*').forEach((el) => {
            const r = el.getBoundingClientRect();
            if (r.width > 0 && r.right > de.clientWidth + 2) {
              bad.push(`${el.tagName.toLowerCase()}.${(el.className || '').toString().split(' ')[0]} right=${Math.round(r.right)}`);
            }
          });
        }
        return { over, bad: [...new Set(bad)].slice(0, 5) };
      });

      // 移动端特有元素是否按预期显示
      const mobileState = await page.evaluate(() => {
        const vis = (sel) => {
          const el = document.querySelector(sel);
          if (!el) return null;
          return getComputedStyle(el).display !== 'none';
        };
        return {
          sidebarBackdropExists: !!document.querySelector('.sidebar-backdrop'),
          menuBtnVisible: vis('.menu-btn'),
          sidebarVisible: vis('.sidebar'),
          mobileOnlyVisible: vis('.mobile-only'),
          desktopTableVisible: vis('.desktop-only'),
        };
      });

      const file = `${OUT}/${vpName}-${name}.png`;
      await page.screenshot({ path: file });

      summary.push({
        vp: vpName,
        route: path,
        overflowX: overflow.over,
        overflowEls: overflow.bad,
        errors,
        ...mobileState,
      });

      await page.close();
    }
  }

  // 移动端抽屉交互测试
  const page = await browser.newPage();
  await page.setViewport({ width: 390, height: 844, deviceScaleFactor: 3 });
  await page.goto(BASE + '/status', { waitUntil: 'networkidle2', timeout: 45000 });
  await new Promise((r) => setTimeout(r, 600));
  const drawer = { before: null, afterClick: null };
  drawer.before = await page.evaluate(() => {
    const sb = document.querySelector('.sidebar');
    return sb ? getComputedStyle(sb).transform : null;
  });
  await page.click('.menu-btn').catch(() => {});
  await new Promise((r) => setTimeout(r, 500));
  drawer.afterClick = await page.evaluate(() => {
    const sb = document.querySelector('.sidebar');
    const bd = document.querySelector('.sidebar-backdrop');
    return {
      transform: sb ? getComputedStyle(sb).transform : null,
      open: sb ? sb.classList.contains('open') : null,
      backdropShown: bd ? getComputedStyle(bd).display : null,
    };
  });
  await page.screenshot({ path: `${OUT}/mobile-drawer-open.png` });
  await page.close();

  await browser.close();

  console.log('=========== 渲染校验结果 ===========');
  for (const s of summary) {
    const flag = s.errors.length ? '❌' : s.overflowX > 1 ? '⚠️ ' : '✅';
    console.log(
      `${flag} [${s.vp}] ${s.route.padEnd(10)} 横向溢出=${s.overflowX}px ` +
        `menuBtn=${s.menuBtnVisible} mobileOnly=${s.mobileOnlyVisible}` +
        (s.errors.length ? ` 错误=${s.errors.length}` : ''),
    );
    if (s.overflowEls.length) console.log('     溢出元素: ' + s.overflowEls.join(' | '));
    s.errors.slice(0, 4).forEach((e) => console.log('     ' + e));
  }
  console.log('\n抽屉交互:', JSON.stringify(drawer, null, 2));
  console.log('\n截图目录:', OUT);
})();
