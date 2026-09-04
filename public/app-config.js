// ponytail: app-config = update check + maintenance, polled at boot and every 15 min.
// Native exposes real versionCode via RichMusicBridge.appVersion(); web mode compares
// versionName only via RELEASE_BUILD (baked at deploy).
const RM_CFG = window.__RM_CFG = window.__RM_CFG || { busy: false };

function rmMaintenance(cfg) {
  if (!(cfg.maintenance && cfg.maintenance.enabled)) return;
  if (document.getElementById('rm-maint')) return;
  const el = document.createElement('div');
  el.id = 'rm-maint';
  el.style.cssText = 'position:fixed;inset:0;z-index:99999;background:rgba(0,0,0,.94);color:#fff;display:flex;flex-direction:column;align-items:center;justify-content:center;gap:14px;padding:32px;text-align:center;font-family:-apple-system,system-ui,sans-serif';
  el.innerHTML = '<div style="font-size:44px">🛠️</div><div style="font-size:20px;font-weight:800">Sedang Maintenance</div><div style="font-size:14px;opacity:.75;max-width:320px;line-height:1.5"></div>';
  el.lastElementChild.textContent = cfg.maintenance.message || 'Coba lagi sebentar.';
  document.body.appendChild(el);
  document.querySelectorAll('audio,video').forEach(v => { try { v.pause(); } catch {} });
  if (window.RichMusicBridge && window.RichMusicBridge.pause) try { window.RichMusicBridge.pause(); } catch {}
}

function rmUpdate(cfg) {
  const rel = cfg.release;
  if (!rel || !rel.url) return;
  const native = window.RichMusicBridge;
  if (native && native.appVersion) {
    if (Number(native.appVersion()) >= Number(rel.versionCode)) return;
    if (RM_CFG.busy) return;
    RM_CFG.busy = true;
    if (confirm(`Rythmix v${rel.versionName} tersedia. Update sekarang?`)) {
      toast('⏳ Mengunduh update…');
      native.installApk(rel.url);
    } else RM_CFG.busy = false;
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
