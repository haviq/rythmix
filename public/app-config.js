// ponytail: app-config = update check + maintenance, polled at boot and every 15 min.
// No confirm()/alert() — WebView suppresses JS dialogs without a WebChromeClient.
const RM_CFG = window.__RM_CFG = window.__RM_CFG || { busy: false, deferred: null };

function rmPauseAll() {
  document.querySelectorAll('audio,video').forEach(v => { try { v.pause(); } catch {} });
  if (window.RichMusicBridge && window.RichMusicBridge.pause) try { window.RichMusicBridge.pause(); } catch {}
}

function rmMaintenance(cfg) {
  const on = !!(cfg.maintenance && cfg.maintenance.enabled);
  let el = document.getElementById('rm-maint');
  if (!on) { el && el.remove(); return; } // config turned off → overlay must go away
  if (el) { // still on → refresh message only
    el.lastElementChild.textContent = cfg.maintenance.message || 'Coba lagi sebentar.';
    return;
  }
  el = document.createElement('div');
  el.id = 'rm-maint';
  el.style.cssText = 'position:fixed;inset:0;z-index:99999;background:rgba(0,0,0,.94);color:#fff;display:flex;flex-direction:column;align-items:center;justify-content:center;gap:14px;padding:32px;text-align:center;font-family:-apple-system,system-ui,sans-serif';
  el.innerHTML = '<div style="font-size:44px">🛠️</div><div style="font-size:20px;font-weight:800">Sedang Maintenance</div><div style="font-size:14px;opacity:.75;max-width:320px;line-height:1.5"></div>';
  el.lastElementChild.textContent = cfg.maintenance.message || 'Coba lagi sebentar.';
  document.body.appendChild(el);
  rmPauseAll();
}

function rmUpdateModal(name, onYes) {
  const wrap = document.createElement('div');
  wrap.id = 'rm-update';
  wrap.style.cssText = 'position:fixed;inset:0;z-index:99998;background:rgba(0,0,0,.6);display:flex;align-items:center;justify-content:center;padding:24px;font-family:-apple-system,system-ui,sans-serif';
  wrap.innerHTML = '<div style="background:#161616;color:#fff;border-radius:16px;padding:22px;max-width:340px;width:100%;box-shadow:0 12px 40px rgba(0,0,0,.5)"><div style="font-size:17px;font-weight:800;margin-bottom:8px">Update tersedia</div><div style="font-size:14px;opacity:.8;line-height:1.5;margin-bottom:18px"></div><div style="display:flex;gap:10px;justify-content:flex-end"><button id="rm-u-no" style="background:transparent;color:#0A84FF;border:0;font-size:15px;font-weight:600;padding:10px 14px;border-radius:10px">Nanti</button><button id="rm-u-yes" style="background:#0A84FF;color:#fff;border:0;font-size:15px;font-weight:700;padding:10px 18px;border-radius:10px">Update</button></div></div>';
  wrap.children[0].children[1].textContent = `Rythmix v${name} siap diunduh. Update sekarang?`;
  document.body.appendChild(wrap);
  wrap.querySelector('#rm-u-no').onclick = () => {
    wrap.remove();
    RM_CFG.busy = false;
    RM_CFG.deferred = Date.now(); // don't re-ask for 12h
  };
  wrap.querySelector('#rm-u-yes').onclick = () => { wrap.remove(); onYes(); };
}

function rmUpdate(cfg) {
  const rel = cfg.release;
  if (!rel || !rel.url) return;
  const native = window.RichMusicBridge;
  if (native) {
    // old bridges (≤v29) lack appVersion() → treat as 0 so they still get the prompt
    let v = 0;
    try { v = Number(native.appVersion()); } catch {}
    if (!(v >= 0)) v = 0;
    if (v >= Number(rel.versionCode)) return;
    if (RM_CFG.busy || (RM_CFG.deferred && Date.now() - RM_CFG.deferred < 12 * 3600 * 1000)) return;
    RM_CFG.busy = true;
    rmUpdateModal(rel.versionName, () => {
      toast('⏳ Mengunduh update…');
      native.installApk(rel.url);
      RM_CFG.busy = false;
    });
  } else if (window.RELEASE_BUILD && rel.versionName && window.RELEASE_BUILD !== rel.versionName && !RM_CFG.busy) {
    RM_CFG.busy = true;
    toast(`🆕 Rythmix v${rel.versionName} tersedia`);
  }
}

function rmCheckConfig() {
  api('/api/app-config').then(cfg => { rmMaintenance(cfg); rmUpdate(cfg); }).catch(() => {});
}
rmCheckConfig();
setInterval(rmCheckConfig, 15 * 60 * 1000);
