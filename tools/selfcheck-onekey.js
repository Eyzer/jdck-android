// 一键登录注入脚本自检（无需安卓环境）：node tools/selfcheck-onekey.js
// 直接读取 JsUtil 里的 ONE_KEY_JS，用假 DOM 验证「勾选协议 + 点登录 + 补全掩码格」
const fs = require('fs');
const path = require('path');

const src = fs.readFileSync(path.join(__dirname, '../app/src/main/java/cn/xanderye/android/jdck/util/JsUtil.java'), 'utf8');
const JS = [...src.split('private static final String ONE_KEY_JS =')[1].split('public static String oneKeyJs')[0].matchAll(/"([^"]*)"/g)].map(m => m[1]).join('');

class E {
  constructor(tag, txt, cls, attrs) {
    this.tagName = tag.toUpperCase(); this._t = txt; this.className = cls || '';
    this.children = []; this.parentElement = null; this.offsetParent = {};
    this.clicked = 0; this.dispatched = 0; this.value = '';
    this.attrs = attrs || {};
  }
  get innerText() { return this.children.length ? this.children.map(c => c.innerText).join('') : (this._t || ''); }
  click() { this.clicked++; }
  getAttribute(k) { return this.attrs[k] === undefined ? null : this.attrs[k]; }
  dispatchEvent() { this.dispatched++; }
  append(...cs) { cs.forEach(c => { c.parentElement = this; this.children.push(c); }); return this; }
}

function scenario({ onekey, checked, maskBoxes }) {
  const body = new E('body', '');
  const box = new E('div', '', checked ? 'policy_tip-checkbox policy_tip-checkbox-checked' : 'policy_tip-checkbox');
  const btn = new E('span', '同意协议并本机号码一键登录');
  const inputs = [];
  if (onekey) {
    body.append(new E('div', '中国移动为您提供本机号码认证服务'));
    for (let i = 0; i < maskBoxes; i++) {
      const inp = new E('input', '', '', { maxlength: '1', type: 'tel' });
      inputs.push(inp);
      body.append(inp);
    }
    body.append(box, new E('div', '').append(btn));
  } else {
    body.append(new E('input', '', '', { type: 'text' }), new E('a', '登录'));
  }
  return { body, box, btn, inputs };
}

function run(name, cfg, expect) {
  const { body, box, btn, inputs } = scenario(cfg);
  const all = [];
  (function walk(n) { all.push(n); n.children.forEach(walk); })(body);
  global.document = {
    body,
    getElementsByClassName: c => all.filter(e => e.className === c),
    querySelectorAll: sel => all.filter(e => sel.split(',').map(s => s.trim().toUpperCase()).includes(e.tagName)),
  };
  global.window = { frames: [], __jdckPhone: cfg.phone || '' };
  global.getComputedStyle = () => ({ cursor: 'auto' });

  const ret = eval(JS);
  return new Promise(resolve => setTimeout(() => {
    const masked = inputs.map(i => i.value).join('');
    const ok = ret === expect.ret && box.clicked === expect.box && btn.clicked === expect.btn && masked === expect.mask;
    console.log((ok ? 'PASS ' : 'FAIL ') + name + ' | return=' + ret + ' 勾选=' + box.clicked + ' 点登录=' + btn.clicked + ' 掩码=' + (masked || '-'));
    if (!ok) process.exitCode = 1;
    resolve();
  }, 700));
}

(async () => {
  await run('号码认证页-未勾选', { onekey: true, checked: false, maskBoxes: 0, phone: '18712345678' },
    { ret: 'ok', box: 1, btn: 1, mask: '' });
  await run('号码认证页-已勾选', { onekey: true, checked: true, maskBoxes: 0, phone: '18712345678' },
    { ret: 'ok', box: 0, btn: 1, mask: '' });
  await run('号码认证页-四格掩码', { onekey: true, checked: false, maskBoxes: 4, phone: '18712345678' },
    { ret: 'ok', box: 1, btn: 1, mask: '1234' });
  await run('普通账号密码页', { onekey: false, checked: false, maskBoxes: 0, phone: '18712345678' },
    { ret: 'no', box: 0, btn: 0, mask: '' });
})();
