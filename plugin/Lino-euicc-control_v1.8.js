<!-- [KANO_PLUGIN_START] Lino-euicc-control_v1.8.js -->
// KANO 插件版本: v1.8
//   依赖: Magisk 模块 kano-euicc-ctl（特权 APK KanoEuiccCtl）
//   通道: am startservice -n kano.euiccctl/.CtlService --es cmd ...
//         结果读 /data/data/kano.euiccctl/files/result.txt
//
//   v1.1: UI 重构为「折叠标题栏 + 状态网格 + 卡片分区」，对齐同目录
//         「射频增强面版」的视觉语言（CSS 变量主题、acc-card/acc-grid 布局）；
//         新增卡槽识别（物理槽数 / 端口可用性 / 本卡所在槽 / 卡可达性）。
//   v1.0: 初版。profile 列表/启用/禁用/下载；删除需输入 ICCID 二次确认
//         （APK 侧亦做硬校验，绕过前端也无法误删）
//
//   设计说明:
//   - 一律用 root shell (runShellWithRoot)，无 UI 自动化，不受屏幕旋转/锁屏影响
//   - 特权 APK 负责直连 eUICC（OMAPI+ES10c）与委托系统 EuiccService 下载
//   - 插件只做: 下发命令 / 解析结果 / 渲染 / 二次确认
//   - 挂载在 .functions-container **之后**（与同目录插件一致），不用 appendChild
//
//   ⚠ 已知限制:
//   切换 profile(启用/禁用) 后，系统框架的 EuiccManager 缓存可能滞后，
//   导致「下载」暂时报 isEnabled=false。等待或重启即恢复。
//   若卡片状态异常(gsm.sim.state 显示 ABSENT)，重启设备是可靠恢复手段。
//<script>
(() => {
  if (document.getElementById('LINO_EUICC_PANEL')) return;

  const PKG = 'kano.euiccctl';
  const SERVICE = `${PKG}/.CtlService`;
  const RESULT = `/data/data/${PKG}/files/result.txt`;
  const PANEL = 'LINO_EUICC_PANEL';
  const TOGGLE = 'lino_euicc_toggle';
  const BODY = 'lino_euicc_body';

  const state = {
    busy: false, eid: '', profiles: [], slot: {}, lastRaw: '',
    euiiccSlots: [],      // 各槽是否为可用 eUICC，如 [false, true]
    subs: [],             // 各槽订阅信息（含 ICCID/运营商/是否嵌入式）
    selectedSlot: -1,     // 当前操作的目标槽位；-1 = 自动
  };

  // ---------- shell 桥接 ----------
  // 参考同目录插件写法：直接取 res.content；不做 success 强判（宿主实现不一）
  const sh = async (cmd, timeout = 30000) => {
    try {
      if (typeof runShellWithRoot !== 'function') return '';
      const r = await runShellWithRoot(cmd + ' 2>&1', timeout);
      return String((r && r.content) || '').trim();
    } catch (e) { return ''; }
  };

  const toast = (msg, color) => {
    if (typeof createToast === 'function') { try { createToast(msg, color || 'pink'); } catch (e) {} }
  };

  const esc = (s) => String(s == null ? '' : s)
    .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;').replace(/'/g, '&#39;');

  const q = (s) => `'${String(s == null ? '' : s).replace(/'/g, `'\\''`)}'`;

  // ---------- 自建弹层（替代 window.prompt / confirm）----------
  /**
   * 本环境必须自建弹层，不能用 window.prompt / confirm。
   *
   * 原因（实测证据）：UFI-TOOLS 的 APK 中
   *   onJsPrompt / onJsAlert / onJsConfirm / WebChromeClient 均为 0 处，
   *   即宿主未设置 WebChromeClient。Android WebView 在这种情况下
   *   prompt() 直接返回 null、confirm() 返回 false，且不显示任何界面 ——
   *   表现为「点了按钮毫无反应」。
   * 旁证：工作区内 5 个既有插件无一处使用 prompt/confirm，全部自建弹层。
   *
   * 因此统一改用本模块的 uiPrompt / uiConfirm，接口保持 Promise 语义：
   *   uiPrompt(msg, def) -> Promise<string|null>   null 表示取消
   *   uiConfirm(msg)     -> Promise<boolean>
   */
  const MODAL_ID = 'lino_modal_overlay';

  const _esc = (t) => String(t == null ? '' : t)
    .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');

  const _modal = (opts) => new Promise((resolve) => {
    const old = document.getElementById(MODAL_ID);
    if (old) old.remove();

    const ov = document.createElement('div');
    ov.id = MODAL_ID;
    ov.style.cssText = 'position:fixed;top:0;left:0;right:0;bottom:0;'
      + 'background:rgba(0,0,0,.72);backdrop-filter:blur(4px);-webkit-backdrop-filter:blur(4px);'
      + 'z-index:99999;display:flex;align-items:center;justify-content:center;padding:18px;';

    const panel = document.createElement('div');
    // html 模式放宽宽度并允许纵向滚动（使用说明较长）
    const maxW = opts.width || (opts.html ? '560px' : '420px');
    panel.style.cssText = 'max-width:' + maxW + ';width:100%;box-sizing:border-box;'
      + 'max-height:82vh;overflow-y:auto;'
      + 'background:rgba(20,28,38,.98);border:1px solid rgba(255,255,255,.14);'
      + 'border-radius:14px;padding:18px 20px;color:#c8d6e5;'
      + 'font-size:.82rem;line-height:1.6;box-shadow:0 18px 50px rgba(0,0,0,.75);';

    const msg = document.createElement('div');
    if (opts.html) {
      // 允许富文本与链接；内容由本插件自己生成，不含外部输入
      msg.style.cssText = 'word-break:break-word;margin-bottom:12px;';
      msg.innerHTML = opts.message;
    } else {
      msg.style.cssText = 'white-space:pre-wrap;word-break:break-all;margin-bottom:12px;';
      msg.innerHTML = _esc(opts.message);
    }
    panel.appendChild(msg);

    let input = null;
    if (opts.placeholder !== undefined) {
      input = document.createElement('input');
      input.type = 'text';
      input.value = opts.placeholder;
      input.style.cssText = 'width:100%;box-sizing:border-box;padding:8px 10px;'
        + 'border-radius:8px;border:1px solid rgba(255,255,255,.2);'
        + 'background:rgba(0,0,0,.32);color:#e6eef7;font-size:.78rem;'
        + 'font-family:monospace;margin-bottom:14px;';
      panel.appendChild(input);
    }

    const row = document.createElement('div');
    row.style.cssText = 'display:flex;gap:8px;justify-content:flex-end;';
    const mkBtn = (text, primary, danger) => {
      const b = document.createElement('button');
      b.textContent = text;
      b.style.cssText = 'cursor:pointer;border-radius:8px;padding:6px 14px;font-size:.76rem;'
        + 'border:1px solid ' + (danger ? 'rgba(255,90,90,.45)' : 'rgba(255,255,255,.18)') + ';'
        + 'background:' + (primary ? 'rgba(76,175,80,.9)' : 'rgba(255,255,255,.08)') + ';'
        + 'color:' + (primary ? '#fff' : (danger ? '#ff9b9b' : '#dce8f5')) + ';';
      return b;
    };
    const okBtn = mkBtn(opts.okText || '确定', true, false);
    const cancelBtn = mkBtn(opts.cancelText || '取消', false, false);

    const done = (val) => { ov.remove(); resolve(val); };
    okBtn.onclick = () => done(input ? input.value : true);
    cancelBtn.onclick = () => done(input ? null : false);
    ov.onclick = (e) => { if (e.target === ov) done(input ? null : false); };

    if (input) {
      input.addEventListener('keydown', (e) => {
        if (e.key === 'Enter') { e.preventDefault(); done(input.value); }
        else if (e.key === 'Escape') { e.preventDefault(); done(null); }
      });
      // 延迟聚焦，避免部分 WebView 在插入瞬间聚焦失败
      setTimeout(() => { try { input.focus(); input.select(); } catch (e) {} }, 60);
    }

    if (opts.showCancel !== false) row.appendChild(cancelBtn);
    row.appendChild(okBtn);
    panel.appendChild(row);
    ov.appendChild(panel);
    document.body.appendChild(ov);
  });

  /**
   * 信息展示弹层（富文本）。仅一个「知道了」按钮，点任意处关闭。
   * 用于「使用说明」这类纯阅读内容。
   */
  // ---------- 使用说明 ----------
  const REPO_URL = 'https://github.com/linote99/lino-euicc-control';

  /**
   * 使用说明弹层（含仓库链接）。
   *
   * 用 uiInfo 自建富文本弹层，而非 window.alert ——
   * 本环境 WebView 未设置 WebChromeClient，alert/confirm/prompt 均无界面。
   */
  const showHelp = () => {
    const link = 'color:#7fb3ff;text-decoration:underline;word-break:break-all;';
    const sec = 'margin:14px 0 6px;font-weight:600;color:#e8f0fa;font-size:.84rem;';
    const li = 'margin:3px 0 3px 2px;';
    const html = ''
      + '<div style="font-size:.95rem;font-weight:600;color:#e8f0fa;margin-bottom:4px;">'
      +   '\ud83d\udcf6 eSIM 配置管理 \u00b7 使用说明</div>'
      + '<div style="font-size:.75rem;color:rgba(255,255,255,.45);margin-bottom:10px;">'
      +   '配合 Magisk 模块 Lino euicc control 使用</div>'
      + '<div style="' + sec + '">能做什么</div>'
      + '<div style="' + li + '">\u00b7 <b>查看配置</b>：卡内所有 eSIM 配置的昵称、提供方、ICCID 与启用状态</div>'
      + '<div style="' + li + '">\u00b7 <b>启用 / 禁用</b>：切换当前使用的配置（禁用会断网，属卡片行为）</div>'
      + '<div style="' + li + '">\u00b7 <b>改名</b>：给配置起个自己认得出的名字，留空即清除</div>'
      + '<div style="' + li + '">\u00b7 <b>下载配置</b>：粘贴激活码或扫码，直接下载并安装（无需手动操作）</div>'
      + '<div style="' + li + '">\u00b7 <b>删除配置</b>：需手动输入该配置的 ICCID 二次确认</div>'
      + '<div style="' + li + '">\u00b7 <b>卡槽识别</b>：双卡时显示选择器，指明操作哪一张</div>'
      + '<div style="' + sec + '">首次使用</div>'
      + '<div style="' + li + '">1. 先刷入 Magisk 模块并<b>重启</b>（提供特权能力）</div>'
      + '<div style="' + li + '">2. 把插件文件拖入 UFI-TOOLS（无需重启）</div>'
      + '<div style="' + li + '">3. 需要扫码时点「下载扫码组件」（约 251 KB，只下一次）</div>'
      + '<div style="margin-top:6px;font-size:.75rem;color:rgba(255,255,255,.5);">'
      +   '未刷模块时，插件能打开但读取配置会失败。</div>'
      + '<div style="' + sec + '">常见问题</div>'
      + '<div style="' + li + '">\u00b7 <b>扫码点不动</b>：先点「下载扫码组件」；仍不行点「检测」看哪一步失败</div>'
      + '<div style="' + li + '">\u00b7 <b>改名 / 删除没反应</b>：需模块 v2.9 起（修复了 lpac 初始化问题）</div>'
      + '<div style="' + li + '">\u00b7 <b>下载报错</b>：确认模块已刷入并重启；激活码需为 lpa:1$ 开头</div>'
      + '<div style="' + li + '">\u00b7 <b>切换后网络短暂中断</b>：卡片刷新配置的正常行为，稍等即恢复</div>'
      + '<div style="' + sec + '">注意</div>'
      + '<div style="' + li + '">\u00b7 <b>删除配置不可逆</b>，操作前请确认 ICCID</div>'
      + '<div style="' + li + '">\u00b7 需要 root（Magisk）与 UFI-TOOLS 高级功能（root shell）</div>'
      + '<div style="margin-top:16px;padding-top:12px;border-top:1px solid rgba(255,255,255,.1);">'
      +   '<div style="font-size:.75rem;color:rgba(255,255,255,.5);margin-bottom:6px;">'
      +     '源码、版本历史与最新下载：</div>'
      +   '<a href="' + REPO_URL + '" target="_blank" rel="noopener" style="' + link + '">'
      +     REPO_URL + '</a></div>';
    uiInfo(html);
  };

  const uiInfo = (html, okText = '知道了') => _modal({
    message: html,
    html: true,
    okText,
    showCancel: false,
    width: '580px',
  });

  /** 文本输入弹层；返回输入值，取消返回 null */
  const uiPrompt = (message, def = '') => _modal({
    message,
    placeholder: def,
    okText: '确定',
    cancelText: '取消',
  });

  /** 确认弹层；返回 true/false */
  const uiConfirm = (message, okText = '确定') => _modal({
    message,
    okText,
    cancelText: '取消',
  });

  // ---------- 扫码组件（jsQR）按需加载 ----------
  /**
   * jsQR 不再内嵌（原占 251 KB，是插件体积的 88%），改为按需下载到设备本地。
   *
   * 优点：插件本体从 279 KB 降到 ~28 KB；不用扫码的人无需承担这份体积。
   *
   * 组件路径：/sdcard/ufitools/lib/jsQR.min.js
   *   —— 放在 /sdcard 下便于用户手动替换（设备无法直连 GitHub 时）。
   *   下载后校验 sha256，不匹配则删除并报错，不加载可疑文件。
   */
  const PLUGIN_VERSION = '1.8';
  const JQR_DIR  = '/sdcard/ufitools/lib';
  const JQR_PATH = JQR_DIR + '/jsQR.min.js';
  const JQR_SHA  = 'bc40c8a15196236b2314db0856f72ca0b49980cd5413b8c852a7349f5fee0859';
  const JQR_SIZE = 256885;
  const JQR_VER  = 'jsQR 1.4.0';
  /**
   * 下载源（按可靠性排序，逐个尝试，全部校验 sha256）。
   *
   * 选源依据：在设备上实测每个源下载后比对 sha256，结果：
   *   GitHub Release        256885 ✅   jsDelivr(cdn/gcore/testingcf/fastly) 256885 ✅
   *   ghproxy.net 镜像      256885 ✅   unpkg(jsqr@1.4.0)                    256885 ✅
   *   raw.githubusercontent 32768  ❌   staticaly                           32768  ❌
   * 后两者返回 HTTP 200 但**内容被截断**（仅 32 KB），属静默损坏，故不采用。
   * jsDelivr 用固定 tag（@assets-v1）而非分支名，避免上游改动导致校验失败。
   */
  const JQR_URLS = [
    'https://cdn.jsdelivr.net/gh/linote99/lino-euicc-control@assets-v1/third_party/jsQR.min.js',
    'https://fastly.jsdelivr.net/gh/linote99/lino-euicc-control@assets-v1/third_party/jsQR.min.js',
    'https://gcore.jsdelivr.net/gh/linote99/lino-euicc-control@assets-v1/third_party/jsQR.min.js',
    'https://github.com/linote99/lino-euicc-control/releases/download/assets-v1/jsQR.min.js',
    'https://ghproxy.net/https://raw.githubusercontent.com/linote99/lino-euicc-control/main/third_party/jsQR.min.js',
  ];

  let jqrLoaded = false;

  // ---------- 组件状态同步（参考《插件综合收纳箱》的设备端同步范式）----------
  /**
   * 借鉴同目录《插件综合收纳箱(跨端同步版)》，要点：
   *   1) 单一数据源 = 设备端文件；localStorage 仅作本地缓存（秒开 + 离线兜底）
   *   2) 设备端用 base64 编解码 —— 避免 JSON 的引号/反斜杠在 shell 中被解析
   *   3) 原子写（.tmp + mv）—— 避免写一半损坏
   *   4) 逐键时间戳 + 用设备时间校准 clockOffset —— 多端各改不同项互不覆盖，
   *      且不受各端本地时钟偏差影响
   *   5) 拉取后合并；合并结果与远端不一致 => 本地更新，回推一次即收敛
   *
   * 与收纳箱的差异：本插件只需记录「扫码组件是否安装」，故文档结构更简，
   * 但完整保留其同步机制。
   */
  const SYNC_DOC_VERSION = 1;
  const DEVICE_DIR  = '/data/lino_euicc_ctl';
  const DEVICE_FILE = DEVICE_DIR + '/scan_state.json';
  const DEVICE_TMP  = DEVICE_DIR + '/.scan_state.tmp';
  const ABSENT_MARKER = '__LINO_ABSENT__';
  const LOCAL_CACHE_KEY = 'lino_euicc_scan_state_v1';

  let syncDoc = { v: SYNC_DOC_VERSION, savedAt: 0, keys: {} };
  let clockOffset = 0;         // 设备时间 - 本机时间
  let syncStatus = 'unknown';  // unknown | ok | absent | unavailable
  let pushInFlight = false, pushPending = false, pullInFlight = false;

  const now = () => Date.now() + clockOffset;

  const b64encode = (str) => {
    const bytes = new TextEncoder().encode(str);
    let bin = '';
    bytes.forEach((b) => { bin += String.fromCharCode(b); });
    return btoa(bin);
  };
  const b64decode = (b64) => {
    const bin = atob(String(b64).replace(/\s+/g, ''));
    return new TextDecoder().decode(Uint8Array.from(bin, (c) => c.charCodeAt(0)));
  };

  const emptyDoc = () => ({ v: SYNC_DOC_VERSION, savedAt: 0, keys: {} });

  const normalizeDoc = (d) => {
    const out = emptyDoc();
    if (!d || typeof d !== 'object') return out;
    out.savedAt = Number(d.savedAt) || 0;
    const src = (d.keys && typeof d.keys === 'object') ? d.keys : {};
    Object.keys(src).forEach((k) => {
      const x = src[k];
      if (!x) return;
      out.keys[k] = {
        installed: !!x.installed,
        size: Number(x.size) || 0,
        sha256: String(x.sha256 || ''),
        source: String(x.source || ''),
        t: Number(x.t) || 0,
      };
    });
    return out;
  };

  /** 逐键时间戳合并：同键取 t 较大者 */
  const mergeDocs = (a, b) => {
    const out = emptyDoc();
    out.savedAt = Math.max(Number(a.savedAt) || 0, Number(b.savedAt) || 0);
    const names = {};
    Object.keys(a.keys || {}).forEach((n) => { names[n] = 1; });
    Object.keys(b.keys || {}).forEach((n) => { names[n] = 1; });
    Object.keys(names).forEach((n) => {
      const x = a.keys[n], y = b.keys[n];
      if (!x) out.keys[n] = y;
      else if (!y) out.keys[n] = x;
      else out.keys[n] = (Number(y.t) || 0) > (Number(x.t) || 0) ? y : x;
    });
    return out;
  };

  const docEquals = (a, b) => JSON.stringify(a) === JSON.stringify(b);
  const persistLocal = () => {
    try { localStorage.setItem(LOCAL_CACHE_KEY, JSON.stringify(syncDoc)); } catch (e) {}
  };

  /** 写入设备端（base64 + 原子写） */
  const pushDevice = async () => {
    if (pushInFlight) { pushPending = true; return; }
    pushInFlight = true;
    try {
      const b64 = b64encode(JSON.stringify(syncDoc));
      await sh(`mkdir -p ${DEVICE_DIR} && printf '%s' '${b64}' | base64 -d > ${DEVICE_TMP}`
        + ` && mv ${DEVICE_TMP} ${DEVICE_FILE} && chmod 666 ${DEVICE_FILE}`, 20000);
      const c = await sh(`test -s ${DEVICE_FILE} && echo OK || echo NO`, 10000);
      syncStatus = (c || '').indexOf('OK') >= 0 ? 'ok' : 'unavailable';
    } finally {
      pushInFlight = false;
      if (pushPending) { pushPending = false; pushDevice(); }
    }
  };

  /** 拉取并合并；顺带用设备时间校准 clockOffset */
  const pullDevice = async () => {
    if (pullInFlight) return false;
    pullInFlight = true;
    const t0 = Date.now();
    try {
      const raw = await sh(`echo "LINO_NOW=$(date +%s)"; `
        + `test -f ${DEVICE_FILE} && base64 ${DEVICE_FILE} | tr -d '\\n' || echo ${ABSENT_MARKER}`, 15000);
      const t1 = Date.now();
      if (!raw) { syncStatus = 'unavailable'; return false; }

      const m = raw.match(/LINO_NOW=(\d+)/);
      if (m) clockOffset = Number(m[1]) * 1000 - Math.round((t0 + t1) / 2);

      const body = raw.replace(/LINO_NOW=\d+\s*/, '').trim();
      if (!body || body.indexOf(ABSENT_MARKER) !== -1) {
        syncStatus = 'absent';
        await pushDevice();
        return false;
      }
      let remote;
      try { remote = normalizeDoc(JSON.parse(b64decode(body))); }
      catch (e) { syncStatus = 'unavailable'; return false; }

      const before = normalizeDoc(syncDoc);
      syncDoc = mergeDocs(syncDoc, remote);
      if (!docEquals(before, syncDoc)) persistLocal();
      syncStatus = 'ok';
      if (!docEquals(syncDoc, remote)) await pushDevice();
      return true;
    } finally {
      pullInFlight = false;
    }
  };

  /** 记录组件状态（本地缓存 + 推设备端） */
  const stateMark = async (installed, source, size, sha) => {
    syncDoc.keys['jsQR'] = {
      installed: !!installed, size: Number(size) || 0,
      sha256: String(sha || ''), source: String(source || ''), t: now(),
    };
    syncDoc.savedAt = now();
    persistLocal();
    await pushDevice();
  };


  // ---------- 安装状态记录（落盘，便于多端同步与排障）----------
  /**
   * 在 /sdcard/ufitools/ 下维护一份状态文件。
   *
   * 用途：
   *   1) 记录组件的安装事实（版本、大小、sha256、来源、时间），
   *      换端/换浏览器打开时能直接读到状态，不必每次重新探测大文件
   *   2) 排障依据：能看出「什么时候、从哪个源、装了哪个版本」
   *
   * 位置选在 /sdcard/ufitools/ 而非应用私有目录：
   *   /sdcard 是外部存储，各端与用户都可访问，便于跨端同步与手动检查。
   * 注意：状态文件只作记录，**组件的真实存在性仍以实际文件为准**
   *   （用户可能手动删除组件而未更新状态），故渲染时以文件探测结果为准，
   *   状态文件用于补充「来源/时间」等信息。
   */
  const JQR_STATE = '/sdcard/ufitools/euicc-scan.json';

  const stateWrite = async (obj) => {
    try {
      const json = JSON.stringify(obj);
      // 用单引号包裹并转义，避免 JSON 里的双引号被 shell 解析
      const safe = json.replace(/'/g, "'\\''");
      await sh(`mkdir -p ${JQR_DIR} && printf '%s' '${safe}' > ${JQR_STATE}`, 15000);
      return true;
    } catch (e) { return false; }
  };

  const stateClear = async () => {
    await sh(`rm -f ${JQR_STATE}`, 10000);
  };

  /** 记录一次安装 */
  const stateMarkInstalled = async (source) => {
    await stateWrite({
      component: 'jsQR',
      version: JQR_VER,
      size: JQR_SIZE,
      sha256: JQR_SHA,
      source: source || '(手动放置)',
      installedAt: new Date().toISOString(),
      pluginVersion: PLUGIN_VERSION,
    });
  };


  /** 组件是否已就位（只读探测，不做网络请求） */
  /** 组件是否已安装（只看文件存在且大小合理；内容校验由 jqrVerify 负责） */
  const jqrInstalled = async () => {
    const r = await sh(`[ -f ${JQR_PATH} ] && wc -c < ${JQR_PATH} || echo 0`, 10000);
    const n = parseInt((r || '0').trim(), 10);
    return Number.isFinite(n) && n > 1000;
  };

  /** 校验已安装组件的 sha256；返回 ok/实际值/期望值 */
  const jqrVerify = async () => {
    const r = await sh(`sha256sum ${JQR_PATH} 2>/dev/null | cut -d' ' -f1`, 20000);
    const got = (r || '').trim().toLowerCase();
    return { ok: got === JQR_SHA, got, want: JQR_SHA };
  };

  /**
   * 把组件内容注入当前页面作用域。
   * 用 new Function 执行而非 <script src>：后者受页面 CSP 限制，
   * 且无法在无网络时复用本地已缓存的内容。
   */
  const jqrInject = (code) => {
    try {
      const f = new Function('self', 'window', 'module', 'exports', code);
      const stub = { window: null, self: null };
      stub.window = stub; stub.self = stub;
      f(stub, stub, undefined, undefined);
      if (typeof stub.jsQR === 'function') { window.jsQR = stub.jsQR; return true; }
      if (typeof window.jsQR === 'function') return true;
      return false;
    } catch (e) {
      setLog('扫码组件注入失败: ' + (e && e.message ? e.message : e), true);
      return false;
    }
  };

  /** 读取设备上的组件并注入（不联网） */
  const jqrLoadLocal = async () => {
    if (jqrLoaded && typeof window.jsQR === 'function') return true;

    // 必须用 base64 -w 76（带换行的 base64），不能用 -w0。
    //
    // 原因：UFI-TOOLS 的 RootShell.sendCommandToSocket() 是逐行读取的
    //   （Kotlin BufferedReader.readLine()），且对每行调用 KanoLog.d() 打日志。
    //   若输出是单行 342KB（base64 -w0），该行会被日志/读取链路截断，
    //   返回的 base64 不完整，解码后注入必然失败。
    //   改成每行 76 字符后，逐行读取与日志都能正常处理。
    const r = await sh(`base64 -w 76 ${JQR_PATH} 2>/dev/null`, 40000);
    const b64 = (r || '').replace(/[^A-Za-z0-9+/=]/g, '');
    // 完整长度 342516。偏小说明仍被截断（宿主限制），明确报出来而不是静默失败。
    if (b64.length < 300000) {
      setLog('扫码组件读取不完整：收到 ' + b64.length + ' 字符（完整应为 342516）。'
        + '\n请改用「下载扫码组件」按钮获取。', true);
      return false;
    }
    if (b64.length < 1000) return false;
    let code;
    try { code = decodeURIComponent(escape(atob(b64))); }
    catch (e) { try { code = atob(b64); } catch (e2) { return false; } }
    const ok = jqrInject(code);
    if (ok) jqrLoaded = true;
    return ok;
  };

  /** 下载组件到设备（多源回退 + sha256 校验） */
  const jqrDownload = async () => {
    const curl = '/data/data/com.minikano.f50_sms/files/curl';
    await sh(`mkdir -p ${JQR_DIR}`, 10000);
    setLog('正在下载扫码组件…', false);

    let lastErr = '';
    for (const url of JQR_URLS) {
      const tmp = JQR_PATH + '.part';
      const r = await sh(
        `${curl} -s -L --max-time 90 -o ${tmp} -w '%{http_code}' ${url}`, 110000);
      const code = (r || '').trim().slice(-3);
      if (code !== '200') { lastErr = `HTTP ${code || '失败'} (${url.split('/')[2]})`; continue; }

      // 校验大小与 sha256
      const sz = await sh(`wc -c < ${tmp}`, 10000);
      const n = parseInt((sz || '0').trim(), 10);
      if (n !== JQR_SIZE) { lastErr = `大小不符 ${n}/${JQR_SIZE}`; await sh(`rm -f ${tmp}`, 8000); continue; }
      const hv = await sh(`sha256sum ${tmp} | cut -d' ' -f1`, 20000);
      const got = (hv || '').trim().toLowerCase();
      if (got !== JQR_SHA) { lastErr = `sha256 不符 ${got.slice(0,12)}…`; await sh(`rm -f ${tmp}`, 8000); continue; }

      await sh(`mv ${tmp} ${JQR_PATH} && chmod 644 ${JQR_PATH}`, 15000);
      await stateMark(true, url, JQR_SIZE, JQR_SHA);   // 记录安装（本地+设备端同步）
      await stateMarkInstalled(url);       // 记录安装来源与时间（供多端同步/排障）
      jqrLoaded = false;
      const ok = await jqrLoadLocal();
      setLog(ok ? '扫码组件已下载并加载。' : '组件已下载，但注入失败。', !ok);
      toast(ok ? '扫码组件已就绪' : '组件注入失败', ok ? 'green' : 'red');
      return ok;
    }
    await sh(`rm -f ${JQR_PATH}.part`, 8000);
    setLog('扫码组件下载失败：' + lastErr
      + '\n可手动下载后放入 ' + JQR_PATH, true);
    toast('下载失败', 'red');
    return false;
  };

  /** 卸载组件，释放空间 */
  const jqrUninstall = async () => {
    if (!(await jqrInstalled())) { setLog('扫码组件未安装。', false); toast('未安装', 'pink'); return; }
    const typed = await uiConfirm('确认卸载扫码组件？\n\n'
      + JQR_PATH + '\n卸载后「扫码」功能将不可用，其余功能不受影响。', '卸载');
    if (!typed) { setLog('已取消卸载。', false); return; }
    await sh(`rm -f ${JQR_PATH}`, 15000);
    await stateMark(false, '', 0, '');
    await stateClear();
    jqrLoaded = false;
    try { delete window.jsQR; } catch (e) { window.jsQR = undefined; }
    if (await jqrInstalled()) { setLog('卸载失败，请检查文件权限。', true); toast('卸载失败', 'red'); return; }
    setLog('扫码组件已卸载。', false);
    toast('已卸载', 'green');
    renderScanState();
  };

  /**
   * 组件检测：把每一步的实测结果直接展示出来。
   *
   * 排查「放了文件却用不了」时，需要知道卡在哪一步 —— 是文件不在、
   * 校验不过、shell 读不到、还是注入失败。逐项报告，不猜。
   */
  const jqrDiagnose = async () => {
    const lines = [];
    const ok = (b) => b ? '✅' : '❌';

    await pullDevice();
    lines.push(`${ok(syncStatus === 'ok')} 设备端同步: ${syncStatus}`
      + (syncStatus === 'ok' ? '（已读取设备状态）'
        : syncStatus === 'absent' ? '（设备端无记录，已写入）'
        : '（不可用，仅本地）'));
    lines.push(`   ${DEVICE_FILE}`);
    const mt = syncDoc.keys['jsQR'];
    lines.push(`   记录: ${mt ? ('installed=' + mt.installed + ' size=' + mt.size) : '(无)'}`);
    lines.push('');

    // 1) 宿主能力：runShellWithRoot 是否存在
    const hasShell = (typeof runShellWithRoot === 'function');
    lines.push(`${ok(hasShell)} root shell 可用: ${hasShell ? '是' : '否（请开启高级功能）'}`);
    if (!hasShell) { setLog(lines.join('\n'), true); return; }

    // 2) 文件是否存在 + 大小
    const szRaw = await sh(`[ -f ${JQR_PATH} ] && wc -c < ${JQR_PATH} || echo 0`, 15000);
    const size = parseInt((szRaw || '0').trim(), 10);
    lines.push(`${ok(size === JQR_SIZE)} 文件存在: ${JQR_PATH}`);
    lines.push(`   实测大小 ${size} / 期望 ${JQR_SIZE} 字节`);

    // 目录信息（文件不存在时帮助定位路径问题）
    const lsOut = await sh(`ls -la ${JQR_DIR}/ 2>&1 | head -5`, 15000);
    lines.push(`   目录内容: ${(lsOut || '(空)').replace(/\n/g, ' | ')}`);

    if (size !== JQR_SIZE) {
      lines.push('');
      lines.push('→ 文件大小不符，可能是：路径不对、复制不完整、或文件被改动。');
      lines.push('  可点「下载扫码组件」自动获取。');
      setLog(lines.join('\n'), true);
      return;
    }

    // 3) sha256 校验
    const hv = await sh(`sha256sum ${JQR_PATH} 2>/dev/null | cut -d' ' -f1`, 25000);
    const got = (hv || '').trim().toLowerCase();
    lines.push(`${ok(got === JQR_SHA)} sha256 校验: ${got.slice(0, 16)}…`);
    if (got !== JQR_SHA) {
      lines.push(`   期望: ${JQR_SHA.slice(0, 16)}…`);
      lines.push('→ 文件内容与预期不符，建议「重新下载」。');
    }

    // 4) shell 能否完整读取（这是最常见的问题：单行过大被截断）
    const b64Raw = await sh(`base64 -w 76 ${JQR_PATH} 2>/dev/null`, 45000);
    const b64Len = ((b64Raw || '').replace(/[^A-Za-z0-9+/=]/g, '')).length;
    const expectB64 = 342516;
    lines.push(`${ok(b64Len >= expectB64 - 8)} shell 读取: ${b64Len} / ${expectB64} 字符`);
    if (b64Len < expectB64 - 8) {
      lines.push('→ 读取被截断（宿主对 shell 输出有长度限制）。');
      lines.push('  「扫码」会失败。请告知，我改用分块读取。');
    }

    // 5) 注入 + 实际解码能力
    let injected = false, selfTestOk = false, selfTestMsg = '';
    if (b64Len >= expectB64 - 8) {
      injected = await jqrLoadLocal();
      lines.push(`${ok(injected)} 注入到页面: ${injected ? '成功' : '失败'}`);
      if (injected) {
        // 造一张 21x21 的测试二维码不做，只验证函数可调用
        try {
          const cv = document.createElement('canvas');
          cv.width = cv.height = 100;
          const cx = cv.getContext('2d');
          cx.fillStyle = '#fff'; cx.fillRect(0, 0, 100, 100);
          const img = cx.getImageData(0, 0, 100, 100);
          // 对纯白图调用一次，确认不抛异常（必然识别不出内容，属正常）
          window.jsQR(img.data, 100, 100, { inversionAttempts: 'dontInvert' });
          selfTestOk = true;
          selfTestMsg = 'jsQR 可正常调用';
        } catch (e) {
          selfTestMsg = 'jsQR 调用异常: ' + (e && e.message ? e.message : e);
        }
        lines.push(`${ok(selfTestOk)} 解码器可用性: ${selfTestMsg}`);
      }
    } else {
      lines.push('⏭ 跳过注入检测（读取不完整）');
    }

    lines.push('');
    const allOk = size === JQR_SIZE && got === JQR_SHA
      && b64Len >= expectB64 - 8 && injected;
    lines.push(allOk ? '✅ 全部通过 —— 扫码功能应可正常使用'
                     : '❌ 存在问题，见上方 ❌ 项');
    setLog(lines.join('\n'), !allOk);
    toast(allOk ? '检测通过' : '检测发现问题', allOk ? 'green' : 'red');
  };

  /** 更新界面上的组件状态文案与按钮态 */
  const renderScanState = async () => {
    const st = document.getElementById('lino_jqr_state');
    const btnDl = document.getElementById('lino_jqr_dl');
    const btnRm = document.getElementById('lino_jqr_rm');
    const btnScan = document.getElementById('lino_scan');
    if (!st || !btnDl || !btnRm) return;

    // 判定「已安装」的条件：文件存在且大小合理。
    // 不再依赖 sha256 通过才显示已安装 —— 用户手动放置的文件可能来自
    // 其他渠道，大小对就应视为已安装（校验结果单独提示，不否决安装状态）。
    const szRaw = await sh(`[ -f ${JQR_PATH} ] && wc -c < ${JQR_PATH} || echo 0`, 12000);
    const size = parseInt((szRaw || '0').trim(), 10);
    const exists = Number.isFinite(size) && size > 1000;

    if (exists) {
      const v = await jqrVerify();
      // 来源与时间取自同步记录（设备端 merge 结果）；无记录则视为手动放置
      const meta = syncDoc.keys['jsQR'];
      let tip = '（手动放置）';
      if (meta && meta.t) {
        const d = new Date(meta.t).toISOString().slice(0, 10);
        tip = `（${d} · ${meta.source ? '下载' : '手动放置'}）`;
      }
      st.innerHTML = v.ok
        ? `<span style="color:#7ee2a8;">已安装 ✓</span> <span style="color:rgba(255,255,255,.35);">${size}B ${tip}</span>`
        : `<span style="color:#ffb060;">已安装（校验不同）</span> <span style="color:rgba(255,255,255,.35);">${size}B ${tip}</span>`;
      btnDl.textContent = '重新下载';
      btnRm.style.display = '';
      if (btnScan) btnScan.disabled = false;
    } else {
      st.innerHTML = '<span style="color:rgba(255,255,255,.45);">未安装（扫码功能不可用）</span>';
      btnDl.textContent = '下载扫码组件';
      btnRm.style.display = 'none';
      if (btnScan) btnScan.disabled = true;
    }
  };

  // ---------- 与特权 APK 通信 ----------
  let whitelisted = false;
  const ensureWhitelist = async () => {
    if (whitelisted) return;
    const c = await sh(`dumpsys deviceidle whitelist 2>/dev/null | grep -c '${PKG}'`, 8000);
    if (c.trim() === '0') await sh(`cmd deviceidle whitelist +${PKG}`, 8000);
    whitelisted = true;
  };

  const callCtl = async (cmd, extras = {}, timeout = 45000) => {
    await ensureWhitelist();
    let line = `am startservice -n ${SERVICE} --es cmd ${q(cmd)}`;
    // 双卡场景下带上用户选定的槽位（--ei 为整型 extra）
    if (state.selectedSlot >= 0) line += ` --ei slot ${state.selectedSlot}`;
    for (const k of Object.keys(extras)) line += ` --es ${k} ${q(extras[k])}`;
    await sh(line, 15000);
    const deadline = Date.now() + timeout;
    while (Date.now() < deadline) {
      await new Promise((r) => setTimeout(r, 500));
      const t = await sh(`cat ${RESULT} 2>/dev/null`, 8000);
      if (t) return t;
    }
    return `ERROR=timeout: 未在 ${Math.round(timeout / 1000)}s 内收到结果`;
  };

  const freshCtl = async (cmd, extras = {}, timeout = 45000) => {
    await sh(`rm -f ${RESULT}`, 8000);
    return callCtl(cmd, extras, timeout);
  };

  // ---------- 结果解析 ----------
  const parse = (raw) => {
    const out = { error: '', kv: {}, profiles: [], lines: [], logs: [], subs: [] };
    if (!raw) { out.error = '空结果'; return out; }
    for (const ln of raw.split('\n')) {
      const line = ln.trim();
      if (!line) continue;
      out.lines.push(line);
      if (line.startsWith('ERROR=')) { out.error = line.slice(6); continue; }
      if (line.startsWith('LOG|')) { out.logs.push(line.slice(4)); continue; }
      if (line.startsWith('P|')) {
        const p = { raw: line.slice(2) };
        for (const seg of line.slice(2).split(';')) {
          const i = seg.indexOf('=');
          if (i < 0) continue;
          p[seg.slice(0, i).trim()] = seg.slice(i + 1).trim();
        }
        out.profiles.push(p);
        continue;
      }
      // SUB_SLOT=1;ICCID=xxx;EMBEDDED=false;CARRIER=yyy  —— 复合行，可能有多行（双卡）
      if (line.startsWith('SUB_SLOT=')) {
        const body = line.slice(9);
        const parts = body.split(';');
        const item = { slot: parts[0].trim() };
        for (const seg of parts.slice(1)) {
          const j = seg.indexOf('=');
          if (j > 0) item[seg.slice(0, j).trim()] = seg.slice(j + 1).trim();
        }
        out.subs.push(item);
        // 兼容旧字段：取第一条
        if (out.kv.SUB_SLOT === undefined) out.kv.SUB_SLOT = item.slot;
        continue;
      }
      // SLOT_EUICC_0=true 之类，走通用 KV 即可（键名已含索引）
      const i = line.indexOf('=');
      if (i > 0) out.kv[line.slice(0, i)] = line.slice(i + 1);
    }
    return out;
  };

  // ES10c ProfileState: 1=启用 0=禁用
  const isOn = (p) => p.enabled === 'true' || p.state === '1';
  const stateText = (p) => (p.state === '1' ? '已启用' : p.state === '0' ? '已禁用' : '未知');

  // ---------- UI ----------
  const STYLE = `
    #${PANEL} { width:100%; margin-top:10px; box-sizing:border-box;
      font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif; }
    #${PANEL} .acc-card { background:var(--dark-card-bg, rgba(20,26,35,.85));
      backdrop-filter:blur(20px); -webkit-backdrop-filter:blur(20px);
      border:1px solid rgba(255,255,255,.12); border-radius:12px;
      padding:14px; color:#e0e0e0; }
    #${PANEL} .acc-grid { display:grid; grid-template-columns:repeat(4,1fr);
      gap:8px; margin-bottom:12px; }
    @media (max-width:650px){ #${PANEL} .acc-grid { grid-template-columns:repeat(2,1fr); } }
    #${PANEL} .acc-box { background:rgba(0,0,0,.25); border-radius:8px; padding:8px;
      text-align:center; border:1px solid rgba(255,255,255,.05); }
    #${PANEL} .acc-label { font-size:10px; color:rgba(255,255,255,.5); margin-bottom:4px; }
    #${PANEL} .acc-val { font-size:13px; font-weight:bold; color:#fff; }
    #${PANEL} .acc-section { background:rgba(0,0,0,.2); border:1px solid rgba(255,255,255,.06);
      border-radius:8px; padding:10px; margin-bottom:10px; }
    #${PANEL} .acc-item { display:flex; justify-content:space-between; align-items:center;
      padding:8px 0; border-bottom:1px solid rgba(255,255,255,.04); gap:10px; }
    #${PANEL} .acc-item:last-child { border-bottom:none; }
    #${PANEL} .acc-btn { background:rgba(255,255,255,.08); border:1px solid rgba(255,255,255,.15);
      border-radius:6px; padding:4px 12px; color:#fff; font-size:11px; cursor:pointer;
      transition:all .2s; white-space:nowrap; }
    #${PANEL} .acc-btn:hover { background:rgba(255,255,255,.16); }
    #${PANEL} .acc-btn.active { background:var(--dark-btn-color-active, #4caf50);
      border-color:transparent; font-weight:bold; }
    #${PANEL} .acc-btn.danger { border-color:rgba(255,90,90,.5); color:#ff9b9b; }
    #${PANEL} .acc-btn.danger:hover { background:rgba(255,90,90,.2); }
    #${PANEL} .acc-btn[disabled] { opacity:.45; cursor:not-allowed; }
    #${PANEL} .acc-tag { display:inline-block; padding:1px 7px; border-radius:6px;
      font-size:10px; margin-left:6px; vertical-align:middle; }
    #${PANEL} .tag-on { background:rgba(60,200,120,.22); color:#7ee2a8;
      border:1px solid rgba(60,200,120,.35); }
    #${PANEL} .tag-off { background:rgba(255,255,255,.1); color:#a8b6c6;
      border:1px solid rgba(255,255,255,.16); }
    #${PANEL} .prof-name { font-size:12px; font-weight:bold; color:#fff; word-break:break-all; }
    #${PANEL} .prof-sub { font-size:9px; color:rgba(255,255,255,.4); margin-top:2px;
      word-break:break-all; font-family:monospace; }
    #${PANEL} .acc-input { width:100%; box-sizing:border-box; margin-top:8px; padding:7px 9px;
      border-radius:6px; border:1px solid rgba(255,255,255,.15); background:rgba(0,0,0,.3);
      color:#e6eef7; font-size:11px; font-family:monospace; }
    #${PANEL} .acc-log { white-space:pre-wrap; word-break:break-all; max-height:140px;
      overflow:auto; background:rgba(0,0,0,.3); border-radius:6px; padding:8px;
      font-family:monospace; font-size:10px; color:#9fb3c8; margin-top:8px; }
    #${PANEL} .acc-err { color:#ff9b9b; }
    #${TOGGLE} { background:rgba(255,255,255,.1); border:none; color:#fff;
      border-radius:6px; font-size:12px; padding:2px 10px; cursor:pointer; }
  `;

  const html = `
  <div id="${PANEL}">
    <div class="title" style="margin:6px 0; display:flex; justify-content:space-between; align-items:center;">
      <div style="display:flex; align-items:center; gap:8px;">
        <strong style="color:var(--dark-text-color,#fff); font-size:14px;">📶 eSIM 配置管理</strong>
        <button id="lino_help" style="background:rgba(120,170,255,.18);border:1px solid rgba(120,170,255,.45);color:#a8c8ff;border-radius:6px;font-size:11px;padding:2px 9px;cursor:pointer;">使用说明</button>
        <div id="lino_euicc_toggle_wrap"><button id="${TOGGLE}">展开</button></div>
      </div>
    </div>
    <div class="collapse" id="${BODY}" style="height:0px; overflow:hidden; transition:all .3s ease;">
      <div class="acc-card">
        <div class="acc-grid">
          <div class="acc-box">
            <div class="acc-label">卡槽 / 运营商</div>
            <div class="acc-val" id="lino_slot" style="color:#FF9800;">-</div>
            <div class="acc-label" id="lino_slot_hint" style="margin:2px 0 0;"></div>
            <select id="lino_slot_pick" class="acc-input"
              style="display:none;margin-top:6px;font-size:10px;padding:3px 5px;"></select>
          </div>
          <div class="acc-box">
            <div class="acc-label">配置数量</div>
            <div class="acc-val" id="lino_count" style="color:#2196F3;">-</div>
          </div>
          <div class="acc-box">
            <div class="acc-label">当前启用</div>
            <div class="acc-val" id="lino_active" style="color:#4CAF50;">-</div>
          </div>
          <div class="acc-box">
            <div class="acc-label">卡片状态</div>
            <div class="acc-val" id="lino_health" style="color:#bbb;">-</div>
          </div>
        </div>

        <div class="acc-section">
          <div style="font-size:11px;font-weight:bold;margin-bottom:6px;color:rgba(255,255,255,.8);">
            💳 卡片信息
          </div>
          <div class="acc-item">
            <div style="min-width:0;">
              <div style="font-size:12px;font-weight:bold;color:#fff;">EID</div>
              <div class="prof-sub" id="lino_eid">读取中…</div>
            </div>
          </div>
          <div style="display:flex;gap:8px;justify-content:flex-end;margin-top:6px;">
            <button class="acc-btn" id="lino_refresh">刷新状态</button>
          </div>
        </div>

        <div class="acc-section">
          <div style="font-size:11px;font-weight:bold;margin-bottom:6px;color:rgba(255,255,255,.8);">
            📱 配置列表
          </div>
          <div id="lino_list">
            <div style="font-size:11px;color:rgba(255,255,255,.4);">加载中…</div>
          </div>
        </div>

        <div class="acc-section">
          <div style="font-size:11px;font-weight:bold;margin-bottom:6px;color:rgba(255,255,255,.8);">
            ⬇️ 下载新配置
          </div>
          <div style="font-size:9px;color:rgba(255,255,255,.4);">
            粘贴激活码或点「扫码」识别，随后直接下载并安装（无需手动操作）
          </div>
          <input id="lino_code" class="acc-input" placeholder="lpa:1$smdp.example.com$MATCHID" />
          <input id="lino_qr_file" type="file" accept="image/*" capture="environment" style="display:none;" />
          <div style="display:flex;gap:8px;justify-content:flex-end;margin-top:8px;">
            <button class="acc-btn" id="lino_scan">📷 扫码</button>
            <button class="acc-btn active" id="lino_download">下载并安装</button>
          </div>
          <div style="display:flex;gap:8px;align-items:center;justify-content:space-between;
                      margin-top:8px;padding-top:8px;border-top:1px solid rgba(255,255,255,.07);">
            <span style="font-size:9px;color:rgba(255,255,255,.4);">
              扫码组件 jsQR：<span id="lino_jqr_state">检测中…</span>
            </span>
            <span style="display:flex;gap:6px;flex-shrink:0;">
              <button class="acc-btn" id="lino_jqr_test">检测</button>
              <button class="acc-btn" id="lino_jqr_dl">下载扫码组件</button>
              <button class="acc-btn danger" id="lino_jqr_rm" style="display:none;">卸载</button>
            </span>
          </div>
        </div>

        <div id="lino_log" class="acc-log" style="display:none;"></div>
      </div>
    </div>
  </div>`;

  const setLog = (text, isErr) => {
    const el = document.getElementById('lino_log');
    if (!el) return;
    el.style.display = 'block';
    el.className = 'acc-log' + (isErr ? ' acc-err' : '');
    el.textContent = text;
  };

  const setBusy = (b) => {
    state.busy = b;
    document.querySelectorAll(`#${PANEL} .acc-btn, #${PANEL} .acc-input`)
      .forEach((el) => { el.disabled = b; });
  };

  // ---------- 渲染 ----------
  const renderSlot = () => {
    const slotEl = document.getElementById('lino_slot');
    const healthEl = document.getElementById('lino_health');
    const eidEl = document.getElementById('lino_eid');
    const activeEl = document.getElementById('lino_active');
    const countEl = document.getElementById('lino_count');

    // 卡槽显示：统一用「卡槽N」（1-based），不暴露 0/1 索引与 SIM1/SIM2 这类内部名。
    // 数据来源：APK 的 SUB_SLOT 行（每槽一条），取当前操作目标槽对应的那条。
    const subs = state.subs || [];
    const targetSlot = state.selectedSlot;
    let cur = null;
    if (targetSlot >= 0) {
      cur = subs.find((x) => Number(x.slot) === targetSlot) || null;
    } else if (subs.length) {
      cur = subs[0];
    }

    let slotTxt = '未知';
    let slotDetail = '';
    if (cur) {
      const n = Number(cur.slot);
      slotTxt = '卡槽' + (Number.isFinite(n) ? (n + 1) : '?');
      // 双卡时标注当前看的是哪一张，避免误改
      if (subs.length > 1) slotTxt += `（共 ${subs.length} 张）`;
      slotDetail = cur.CARRIER || '';
      if (cur.ICCID) {
        slotDetail += (slotDetail ? ' · ' : '') + '…' + String(cur.ICCID).slice(-6);
      }
    } else {
      // 回退：无订阅信息时按 SIM 状态推断
      const states = state.slot.SIM_STATE;
      if (states) {
        const arr = states.split(',');
        const idx = arr.findIndex((x) => x && x !== 'ABSENT');
        slotTxt = idx >= 0 ? ('卡槽' + (idx + 1)) : '无卡';
      }
    }
    if (slotEl) slotEl.textContent = slotTxt;
    const health = document.getElementById('lino_slot_hint');
    if (health) health.textContent = slotDetail;

    // 卡槽切换器：仅当存在 ≥2 个可用 eUICC 时显示（单卡时不打扰用户）
    const pick = document.getElementById('lino_slot_pick');
    if (pick) {
      const usable = [];
      state.euiiccSlots.forEach((ok, idx) => { if (ok) usable.push(idx); });
      if (usable.length >= 2) {
        pick.style.display = 'block';
        if (pick.options.length !== usable.length) {
          pick.innerHTML = '';
          for (const idx of usable) {
            const o = document.createElement('option');
            o.value = String(idx);
            o.textContent = `卡槽${idx + 1}`;
            pick.appendChild(o);
          }
        }
        pick.value = String(state.selectedSlot >= 0 ? state.selectedSlot : usable[0]);
      } else {
        pick.style.display = 'none';
        // 只有一个 eUICC：不必显式指定，交给 APK 自动探测
        if (usable.length <= 1) state.selectedSlot = -1;
      }
    }

    const accessible = state.slot.EUICC_ACCESSIBLE !== 'false';
    if (healthEl) {
      healthEl.textContent = accessible ? '可访问' : '不可达';
      healthEl.style.color = accessible ? '#4CAF50' : '#ff9b9b';
    }
    if (eidEl) eidEl.textContent = state.eid || '未读取到';
    if (countEl) countEl.textContent = String(state.profiles.length);
    if (activeEl) {
      const on = state.profiles.filter(isOn);
      activeEl.textContent = on.length ? (on[0].nick || on[0].iccid || '已启用') : '无';
      activeEl.style.color = on.length ? '#4CAF50' : '#FF9800';
    }
  };

  const renderList = () => {
    const list = document.getElementById('lino_list');
    if (!list) return;
    if (!state.profiles.length) {
      list.innerHTML = '<div style="font-size:11px;color:rgba(255,255,255,.4);">未发现配置</div>';
      return;
    }
    list.innerHTML = '';
    state.profiles.forEach((p, idx) => {
      const on = isOn(p);
      const row = document.createElement('div');
      row.className = 'acc-item';
      row.innerHTML = `
        <div style="min-width:0;flex:1;">
          <div class="prof-name">${esc(p.nick || p.name || '(未命名)')}
            <span class="acc-tag ${on ? 'tag-on' : 'tag-off'}">${stateText(p)}</span>
          </div>
          <div class="prof-sub">${esc(p.spn || '')}${p.spn ? ' · ' : ''}${esc(p.iccid || '')}</div>
        </div>
        <div style="display:flex;gap:6px;flex-shrink:0;">
          <button class="acc-btn ${on ? '' : 'active'}" data-act="toggle" data-idx="${idx}">
            ${on ? '禁用' : '启用'}
          </button>
          <button class="acc-btn" data-act="rename" data-idx="${idx}">改名</button>
          <button class="acc-btn danger" data-act="del" data-idx="${idx}">删除</button>
        </div>`;
      list.appendChild(row);
    });

    list.querySelectorAll('button[data-act]').forEach((btn) => {
      btn.onclick = () => {
        const p = state.profiles[Number(btn.getAttribute('data-idx'))];
        if (!p) return;
        const act = btn.getAttribute('data-act');
        if (act === 'toggle') doEnable(p, !isOn(p));
        if (act === 'rename') doRename(p);
        if (act === 'del') doDelete(p);
      };
    });
  };

  // ---------- 操作 ----------
  const refresh = async () => {
    if (state.busy) return;
    setBusy(true);
    setLog('正在读取卡片信息…', false);
    try {
      // 一次拿全：slot 命令含卡槽 + EID + 启用数；list 单独取以实现态
      const slotRaw = await freshCtl('slot');
      const sr = parse(slotRaw);
      state.slot = sr.kv;
      if (sr.kv.EID) state.eid = sr.kv.EID;

      // 收集各槽 eUICC 可用性，决定是否显示卡槽切换器
      const slots = [];
      Object.keys(sr.kv).forEach((k) => {
        const m = /^SLOT_EUICC_(\d+)$/.exec(k);
        if (m) slots[Number(m[1])] = sr.kv[k] === 'true';
      });
      state.euiiccSlots = slots;
      state.subs = sr.subs || [];
      // 若当前选中的槽已无可用 eUICC，回退为自动
      if (state.selectedSlot >= 0 && slots[state.selectedSlot] !== true) state.selectedSlot = -1;
      // gsm.sim.state 作为槽位显示的补充
      const gs = await sh('getprop gsm.sim.state', 8000);
      if (gs) state.slot.SIM_STATE = gs;

      const raw = await callCtl('list');
      const r = parse(raw);
      if (r.error) {
        setLog('读取失败: ' + r.error, true);
        toast('eSIM 读取失败', 'red');
      } else {
        state.eid = r.kv.EID || state.eid;
        state.profiles = r.profiles;
        setLog(`已加载 ${state.profiles.length} 个配置`, false);
      }
      renderSlot();
      renderList();
    } finally {
      setBusy(false);
    }
  };

  const doEnable = async (p, enable) => {
    if (state.busy) return;
    setBusy(true);
    setLog((enable ? '正在启用 ' : '正在禁用 ') + (p.nick || p.iccid) + '…', false);
    try {
      const raw = await freshCtl(enable ? 'enable' : 'disable', { iccid: p.iccid });
      const r = parse(raw);
      if (r.error) {
        setLog('失败: ' + r.error, true);
        toast('操作失败', 'red');
      } else {
        state.profiles = r.profiles.length ? r.profiles : state.profiles;
        if (r.kv.EID) state.eid = r.kv.EID;
        setLog('已' + (enable ? '启用' : '禁用') + ' ' + (p.nick || p.iccid), false);
        toast('操作成功', 'green');
      }
      renderSlot();
      renderList();
    } finally { setBusy(false); }
  };

  /**
   * 修改配置昵称（ES10c SetNickname）。
   *
   * 昵称由卡片保存，与 profile 一同存在 eUICC 内；置空即清除。
   * 约束（与 APK 侧一致）：UTF-8 编码不超过 64 字节 —— 中文约 21 个字。
   */
  const doRename = async (p) => {
    if (state.busy) return;
    const target = p.iccid || '';
    if (!target) { setLog('该配置无 ICCID，无法改名', true); return; }

    const cur = p.nick || '';
    const typed = await uiPrompt(
      '为该配置设置自定义名称\n（留空则清除昵称，最长 64 字节，中文约 21 字）\n\n'
      + 'ICCID: ' + target + '\n当前名称: ' + (cur || '(无)'),
      cur);
    if (typed === null) { setLog('已取消改名', false); return; }

    const nick = String(typed).trim();
    // 前端先做一次长度校验，避免无谓的卡片往返
    const bytes = new TextEncoder().encode(nick).length;
    if (bytes > 64) {
      setLog('昵称过长：UTF-8 ' + bytes + ' 字节（上限 64，中文约 21 字）', true);
      toast('昵称过长', 'red');
      return;
    }

    setBusy(true);
    setLog('正在设置名称…', false);
    try {
      const raw = await freshCtl('nickname', { iccid: target, nick }, 60000);
      const r = parse(raw);
      if (r.error) {
        setLog('改名失败: ' + r.error, true);
        toast('改名失败', 'red');
      } else {
        if (r.profiles.length) state.profiles = r.profiles;
        if (r.kv.EID) state.eid = r.kv.EID;
        setLog(nick ? ('已将 ' + target + ' 命名为「' + nick + '」') : ('已清除 ' + target + ' 的名称'), false);
        toast('改名成功', 'green');
      }
      renderSlot();
      renderList();
    } finally { setBusy(false); }
  };

  const doDelete = async (p) => {
    if (state.busy) return;
    const target = p.iccid || '';
    if (!target) { setLog('该配置无 ICCID，无法删除', true); return; }

    const typed = await uiPrompt(
      '删除配置不可恢复。\n\n请输入该配置的 ICCID 以确认删除：\n' + target, '');
    if (typed === null) { setLog('已取消删除', false); return; }
    if (typed.trim() !== target) {
      setLog('删除已取消：ICCID 不一致\n目标=' + target + '\n输入=' + typed.trim(), true);
      toast('ICCID 不匹配，已取消', 'red');
      return;
    }

    setBusy(true);
    setLog('正在删除 ' + (p.nick || target) + '…', false);
    try {
      const raw = await freshCtl('delete', { iccid: target, confirm: target });
      const r = parse(raw);
      if (r.error) {
        setLog('删除失败: ' + r.error, true);
        toast('删除失败', 'red');
      } else {
        state.profiles = r.profiles.length
          ? r.profiles : state.profiles.filter((x) => x.iccid !== target);
        setLog('已删除 ' + target, false);
        toast('已删除', 'green');
      }
      renderSlot();
      renderList();
    } finally { setBusy(false); }
  };

  // ---------- 扫码 ----------
  /**
   * 拍照/选图扫码。
   *
   * 为何不做 getUserMedia 实时预览：
   *   页面为 http://（非安全上下文），浏览器会拒绝摄像头权限；
   *   且本机无 Play Services，Android 版 BarcodeDetector 的 ML Kit 后端不可用。
   *   故采用「唤起系统相机拍照 → 本地解码」路径：不依赖 GMS、不需要安全上下文。
   *
   * 解码顺序：BarcodeDetector(若可用) → 内嵌 jsQR。
   */
  /** 画到 canvas 并取 RGBA */
  const rasterize = (src, w, h) => {
    const cv = document.createElement('canvas');
    cv.width = w; cv.height = h;
    const ctx = cv.getContext('2d');
    ctx.drawImage(src, 0, 0, w, h);
    return ctx.getImageData(0, 0, w, h);
  };

  /**
   * 轻量图像增强：转灰度 + 对比度拉伸 + 阈值二值化。
   * QR 解码器对光照不均/低对比度敏感，实拍照片先做这一步能明显提高成功率。
   * th 为阈值(0-255)；返回增强后的 ImageData。
   */
  const enhance = (img, th) => {
    const d = img.data;
    const out = new Uint8ClampedArray(d.length);
    // 先算灰度直方图以自适应拉伸
    let min = 255, max = 0;
    const gray = new Uint8ClampedArray(d.length / 4);
    for (let i = 0, j = 0; i < d.length; i += 4, j++) {
      const g = (d[i] * 299 + d[i + 1] * 587 + d[i + 2] * 114) / 1000;
      gray[j] = g;
      if (g < min) min = g;
      if (g > max) max = g;
    }
    const span = Math.max(1, max - min);
    const thr = th != null ? th : (min + max) / 2;
    for (let j = 0; j < gray.length; j++) {
      // 归一化拉伸后再二值化
      const v = (gray[j] - min) * 255 / span;
      const b = v > thr ? 255 : 0;
      const o = j * 4;
      out[o] = out[o + 1] = out[o + 2] = b;
      out[o + 3] = 255;
    }
    return new ImageData(out, img.width, img.height);
  };

  /** 用 jsQR 解码一段 ImageData；失败返回空串 */
  const tryDecode = (imageData) => {
    try {
      if (typeof jsQR === 'function') {
        const r = jsQR(imageData.data, imageData.width, imageData.height,
          { inversionAttempts: 'attemptBoth' });
        if (r && r.data) return String(r.data).trim();
      }
    } catch (e) {}
    return '';
  };

  const decodeFromBlob = async (blob) => {
    const bmp = await createImageBitmap(blob);

    // BarcodeDetector 优先（同步接口不可用时直接跳过）
    try {
      if (typeof BarcodeDetector !== 'undefined') {
        const fmts = await BarcodeDetector.getSupportedFormats();
        if (fmts && fmts.includes('qr_code')) {
          const det = new BarcodeDetector({ formats: ['qr_code'] });
          // 先按原尺寸试
          const bmp2 = await createImageBitmap(blob);
          const codes = await det.detect(bmp2);
          if (bmp2.close) try { bmp2.close(); } catch (e) {}
          if (codes && codes.length) {
            if (bmp.close) try { bmp.close(); } catch (e) {}
            return String(codes[0].rawValue || '').trim();
          }
        }
      }
    } catch (e) { /* 继续走 jsQR */ }

    // 多尺度 × 多阈值重试：实拍条件下单一参数容易失败
    const scales = [1, 0.6, 1.6];
    const baseMax = 1000;
    const ow = bmp.width, oh = bmp.height;
    const results = [];

    for (const sc of scales) {
      const maxSide = baseMax * sc;
      const k = Math.min(1, maxSide / Math.max(ow, oh)) * (sc <= 1 ? 1 : sc);
      const w = Math.max(80, Math.round(ow * Math.min(k, 3)));
      const h = Math.max(80, Math.round(oh * Math.min(k, 3)));
      let img, cv = null;
      try { img = rasterize(bmp, w, h); } catch (e) { continue; }

      // 原图
      results.push(tryDecode(img));
      // 二值化（多个阈值）
      for (const th of [110, 140, 180]) {
        try {
          const e2 = enhance(img, th);
          results.push(tryDecode(e2));
        } catch (e) {}
      }
    }
    if (bmp.close) try { bmp.close(); } catch (e) {}

    for (const r of results) if (r) return r;
    return '';
  };

  const doScan = async () => {
    // 未安装组件则先引导下载（不要静默失败，用户会以为是 bug）
    if (!(await jqrInstalled())) {
      setLog('扫码需要「jsQR 组件」，当前未安装。\n点下方「下载扫码组件」即可（约 251 KB，仅需一次）。', true);
      toast('请先下载扫码组件', 'pink');
      renderScanState();
      return;
    }
    if (typeof window.jsQR !== 'function') {
      const ok = await jqrLoadLocal();
      if (!ok) {
        setLog('扫码组件存在但未能加载。\n可点「检测」查看具体卡在哪一步，或「重新下载」。', true);
        toast('组件加载失败', 'red');
        return;
      }
    }
    __doScanPick();
  };

  const __doScanPick = () => {
    const file = document.getElementById('lino_qr_file');
    if (!file) { setLog('扫码控件缺失', true); return; }
    file.value = '';          // 允许重复选同一张图
    file.click();
  };

  const onQrPicked = async (ev) => {
    const f = ev && ev.target && ev.target.files && ev.target.files[0];
    if (!f) return;
    setLog('正在识别二维码…', false);
    try {
      const text = await decodeFromBlob(f);
      if (!text) {
        setLog('未识别到二维码，请对准后重拍（避免反光/过暗，画面尽量填满）', true);
        toast('未识别到二维码', 'red');
        return;
      }
      const input = document.getElementById('lino_code');
      if (input) input.value = text;
      if (/^lpa:/i.test(text)) {
        setLog('已识别激活码：' + text, false);
        toast('识别成功', 'green');
      } else {
        setLog('识别到内容（非 lpa: 激活码）：' + text, true);
        toast('内容不是激活码', 'pink');
      }
    } catch (e) {
      setLog('识别失败: ' + (e && e.message ? e.message : e), true);
      toast('识别失败', 'red');
    }
  };

  /**
   * 下载并安装配置。
   *
   * 实现路径：交给 APK 的 download 命令，由它直接驱动 OpenEUICC 的
   * liblpac-jni.so 完成 ES9+ 下载 —— 全流程无 UI、无需用户干预。
   *
   * 为何不唤起 OpenEUICC 的下载向导：
   *   该向导(DownloadWizardActivity)存在上游竞态缺陷 —— EuiccChannelManagerService
   *   的连接回调晚于 Activity 的 onSaveInstanceState，showFragment() 抛
   *   IllegalStateException 崩溃。实测多种 flags、预热服务、唤醒屏幕等外部手段
   *   均无法稳定规避（成功率在 0/5 ~ 3/4 间波动）。故改走 lpac，彻底绕开该 Activity。
   *
   * 下载可能耗时较长（SM-DP+ 认证 + 下载 + 安装），故超时给到 3 分钟以上。
   */
  const doDownload = async () => {
    if (state.busy) return;
    const el = document.getElementById('lino_code');
    const c = ((el && el.value) || '').trim();
    if (!c) { setLog('请先填写或扫码获取激活码', true); toast('请填写激活码', 'red'); return; }
    if (!/^lpa:/i.test(c)) { setLog('激活码应以 lpa: 开头', true); toast('格式不对', 'red'); return; }

    setBusy(true);
    setLog('正在下载配置（SM-DP+ 认证中，可能需要 1–3 分钟）…', false);
    try {
      const raw = await freshCtl('download', { code: c }, 210000);
      const r = parse(raw);
      if (r.error) {
        setLog('下载失败: ' + r.error + (r.kv.ERR_TEXT ? '\n' + r.kv.ERR_TEXT : ''), true);
        toast('下载失败', 'red');
        return;
      }
      // 成功：APK 已回带最新列表
      if (r.profiles.length) {
        state.profiles = r.profiles;
        state.eid = r.kv.EID || state.eid;
        renderSlot(); renderList();
      }
      setLog('下载完成。已加载 ' + state.profiles.length + ' 个配置。', false);
      toast('下载成功', 'green');
    } catch (e) {
      setLog('下载异常: ' + (e && e.message ? e.message : e), true);
      toast('下载异常', 'red');
    } finally { setBusy(false); }
  };

  // ---------- 挂载 ----------
  const bind = () => {
    const tg = document.getElementById(TOGGLE);
    const body = document.getElementById(BODY);
    if (tg && body) {
      tg.onclick = () => {
        const open = body.dataset.open === '1';
        body.dataset.open = open ? '0' : '1';
        body.style.height = open ? '0px' : 'auto';
        tg.textContent = open ? '展开' : '收起';
      };
    }
    const sp = document.getElementById('lino_slot_pick');
    if (sp) {
      sp.onchange = () => {
        const v = Number(sp.value);
        state.selectedSlot = Number.isFinite(v) ? v : -1;
        setLog('已切换到卡槽' + (state.selectedSlot + 1) + '，正在刷新…', false);
        refresh();
      };
    }
    const on = (id, fn) => { const el = document.getElementById(id); if (el) el.onclick = fn; };
    on('lino_help', () => showHelp());
    on('lino_refresh', () => refresh());
    on('lino_scan', () => doScan());
    on('lino_jqr_test', async () => {
      const btn = document.getElementById('lino_jqr_test');
      if (btn) btn.disabled = true;
      setLog('正在检测扫码组件…', false);
      try { await jqrDiagnose(); } finally {
        if (btn) btn.disabled = false;
      }
    });
    on('lino_jqr_dl', async () => {
      const btn = document.getElementById('lino_jqr_dl');
      if (btn) btn.disabled = true;
      try { await jqrDownload(); } finally {
        if (btn) btn.disabled = false;
        renderScanState();
      }
    });
    on('lino_jqr_rm', async () => {
      const btn = document.getElementById('lino_jqr_rm');
      if (btn) btn.disabled = true;
      try { await jqrUninstall(); } finally {
        if (btn) btn.disabled = false;
        renderScanState();
      }
    });
    on('lino_download', () => doDownload());
    const qf = document.getElementById('lino_qr_file');
    if (qf) qf.onchange = onQrPicked;
  };

  const mount = () => {
    if (document.getElementById(PANEL)) return true;
    const container = document.querySelector('.functions-container');
    if (!container) return false;

    const style = document.createElement('style');
    style.textContent = STYLE;
    document.head.appendChild(style);

    container.insertAdjacentHTML('afterend', html);
    bind();
    setLog('就绪。点击「展开」查看卡片信息。', false);
    refresh();
    // 探测扫码组件状态；已安装则静默注入，使「扫码」即点即用
    (async () => {
      try {
        await pullDevice();          // 先与设备端同步（多端共享同一份状态）
        await renderScanState();
        if (await jqrInstalled()) await jqrLoadLocal();
      } catch (e) { /* 面板仍可用，忽略 */ }
    })();
    return true;
  };

  const init = () => {
    if (mount()) return;
    const observer = new MutationObserver(() => { if (mount()) observer.disconnect(); });
    observer.observe(document.body, { childList: true, subtree: true });
    setTimeout(() => {
      observer.disconnect();
      if (!mount()) console.warn('[Lino-euicc-control] 未找到 .functions-container，面板未能注入');
    }, 5000);
  };

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init, { once: true });
  } else { init(); }
})();
//</script>
<!-- [KANO_PLUGIN_END] Lino-euicc-control_v1.8.js -->
