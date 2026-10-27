package com.flux.codebuddy

import java.util.Locale

/**
 * Client-side adaptation of the existing CodeBuddy web UI. Nothing is changed
 * on the server; all rules are injected into the WebView only.
 *
 * The overrides are intentionally narrow, and none of them is required for the
 * app to function - the native chrome (home, app bar, settings, recents) is
 * built from Android views and does not depend on the page's DOM:
 *  - seed the existing theme/sidebar preferences on first run
 *  - scale the whole UI by widening the layout viewport and zooming out, which
 *    makes the web app reflow (a CSS transform/zoom would not)
 *  - stop Android/Chrome text autosizing so the CSS sizes are honoured
 *  - let the chat/composer use the full width instead of the 880px cap
 *  - compact the header, composer and login page on short landscape viewports
 *
 * If a future CodeBuddy release renames these classes the rules simply stop
 * applying; the web UI remains fully functional and the native UI is unaffected.
 */
object MobileInjection {

    /** localStorage key holding the selected scale, shared across reloads. */
    const val SCALE_KEY = "cbm-ui-scale"

    private val CSS = """
        html{-webkit-text-size-adjust:100%!important;text-size-adjust:100%!important;}
        :root{--conversation-content-max-width:100%!important;}
        html.cbm-compact{font-size:12px!important;}
        .cbm-compact .app-status-bar{height:44px!important;min-height:44px!important;padding:2px 8px!important;}
        .cbm-compact .app-status-bar button{height:26px!important;width:26px!important;}
        .cbm-compact .composer-stack{padding:0 6px 6px!important;}
        .cbm-compact .composer-card{gap:2px!important;padding-top:3px!important;border-radius:12px!important;}
        .cbm-compact .composer-context-row{min-height:22px!important;padding:0 4px!important;}
        .cbm-compact .composer-seat-chip{height:22px!important;min-height:22px!important;font-size:11px!important;padding:0 6px!important;}
        .cbm-compact .composer-textarea{padding:0 8px!important;font-size:12px!important;min-height:18px!important;}
        .cbm-compact .composer-toolbar{height:24px!important;min-height:24px!important;padding:0 6px 2px!important;}
        .cbm-compact .composer-add,.cbm-compact .composer-send{height:24px!important;min-height:24px!important;}
        .cbm-compact .composer-send{width:24px!important;}
        .cbm-compact .login-page{overflow:hidden!important;justify-content:flex-start!important;padding:4px 0 18px!important;}
        .cbm-compact .login-content{gap:3px!important;padding:2px 0!important;}
        .cbm-compact .login-logo.login-logo-pixel{width:40px!important;height:28px!important;}
        .cbm-compact .login-title{font-size:15px!important;line-height:18px!important;}
        .cbm-compact .login-tagline{font-size:10px!important;line-height:13px!important;}
        .cbm-compact .login-hints{font-size:9px!important;line-height:1.25!important;margin-top:2px!important;}
        .cbm-compact .login-hints p:not(:first-child){display:none!important;}
        .cbm-compact .login-footer{position:absolute!important;bottom:2px!important;}
        .cbm-compact .login-footer a{font-size:9px!important;line-height:12px!important;}
        /* Keep the page from bouncing behind the native bars. */
        html,body{overscroll-behavior:none!important;}
    """.trimIndent()

    /**
     * Document-start script for [scale] (1.00 = 1.0.2 layout).
     *
     * The scale is baked in as a fallback only: after the first load the chosen
     * value lives in localStorage, so a reload picks up a new scale even though
     * the script is registered once per origin. The viewport is rewritten to
     * `width = 760 / scale` with the matching `initial-scale`; `minimum-scale`
     * is low because Chrome otherwise clamps the page scale to 0.25.
     */
    fun documentStartScript(scale: Float): String {
        val value = String.format(Locale.US, "%.2f", UiScale.clamp(scale))
        return """
        (function(){
          var S = $value;
          var seeded = false;
          try {
            var saved = parseFloat(localStorage.getItem('$SCALE_KEY'));
            if (isFinite(saved) && saved >= 0.1 && saved <= 1.5) { S = saved; seeded = true; }
          } catch (e) {}
          if (!seeded) {
            try { localStorage.setItem('$SCALE_KEY', String(S)); } catch (e) {}
          }
          function deviceWidth(){
            // screen.width is the display, not the window: display-cutout
            // padding, freeform windows and split screen make the WebView
            // narrower, which would push the right edge off-screen. The visual
            // viewport in CSS px times its scale is the real window width and
            // stays constant whatever viewport meta is applied.
            try {
              var vv = window.visualViewport;
              if (vv && vv.width && vv.scale) {
                var vw = vv.width * vv.scale;
                if (vw > 200) return vw;
              }
            } catch (e) {}
            var w = 0;
            try { w = Math.max(screen.width || 0, screen.height || 0); } catch (e) {}
            return w || window.innerWidth || 844;
          }
          function viewportContent(){
            var fit = (deviceWidth() / 760) * S;
            if (!(fit > 0.05)) fit = 0.25;
            return 'width=' + Math.round(760 / S) +
              ',initial-scale=' + fit.toFixed(4) +
              ',minimum-scale=0.05,maximum-scale=5';
          }
          var compact = false;
          try {
            var shortSide = Math.min(screen.width || 0, screen.height || 0);
            compact = shortSide > 0 && shortSide <= 560;
          } catch (e) {}
          function applyCompact(){
            try {
              var el = document.documentElement;
              if (el && compact && !el.classList.contains('cbm-compact')) {
                el.classList.add('cbm-compact');
              }
            } catch (e) {}
          }
          var lastViewport = '';
          function fixViewport(){
            applyCompact();
            try {
              var m = document.querySelector('meta[name="viewport"]');
              if (!m) return;
              var v = viewportContent();
              if (v !== lastViewport && m.getAttribute('content') !== v) {
                lastViewport = v;
                m.setAttribute('content', v);
              }
            } catch (e) {}
          }
          window.__cbmApplyScale = function(next){
            var v = parseFloat(next);
            if (!isFinite(v) || v < 0.1 || v > 1.5) return S;
            S = v;
            lastViewport = '';
            try { localStorage.setItem('$SCALE_KEY', String(S)); } catch (e) {}
            fixViewport();
            return S;
          };
          fixViewport();
          document.addEventListener('DOMContentLoaded', fixViewport);
          window.addEventListener('load', fixViewport);
          window.addEventListener('resize', function(){
            lastViewport = '';
            fixViewport();
          });
          // The SPA mounts late; poll briefly instead of observing the whole
          // document (a subtree observer on this app is measurably expensive).
          var n = 0, t = setInterval(function(){ fixViewport(); if (++n > 40) clearInterval(t); }, 60);
          try {
            localStorage.setItem('codebuddy-theme', JSON.stringify({mode:'dark'}));
            if (!localStorage.getItem('codebuddy-sidebar')) {
              localStorage.setItem('codebuddy-sidebar', JSON.stringify({
                isOpen:false,
                sessionsOpen:false,
                sessionsPanelWidth:240,
                workspaceDockOpen:false,
                workspaceDockOpenByAgent:{},
                workspaceDockWidth:480,
                railExpanded:false,
                collapsedAgentGroups:[],
                expandedAgentGroups:[]
              }));
            }
          } catch (e) {}
          var css = `$CSS`;
          function add(){
            try {
              if (document.getElementById('cbm-mobile-css')) return;
              var s = document.createElement('style');
              s.id = 'cbm-mobile-css';
              s.textContent = css;
              (document.head || document.documentElement).appendChild(s);
            } catch (e) {}
          }
          add();
          if (document.readyState === 'loading') {
            document.addEventListener('DOMContentLoaded', add, {once:true});
          }
          setTimeout(add, 0);
        })();
        """.trimIndent()
    }

    /**
     * Applies [scale] to the page that is already loaded and remembers it for
     * the next document. Safe to call before the document-start script ran.
     */
    fun applyScaleScript(scale: Float): String {
        val value = String.format(Locale.US, "%.2f", UiScale.clamp(scale))
        return "(function(){var v='$value';" +
            "try{localStorage.setItem('$SCALE_KEY',v);}catch(e){}" +
            "if(window.__cbmApplyScale)window.__cbmApplyScale(v);})()"
    }

    val LOGIN_CHECK_JS: String =
        "(function(){try{return document.querySelector('.login-btn')?'login':'app'}catch(e){return 'app'}})()"

    val TOGGLE_SIDEBAR_JS: String = """
        (function(){
          function visible(el){return !!(el && (el.offsetWidth||el.offsetHeight||el.getClientRects().length));}
          var close=document.querySelector('.sessions-panel-mobile-close');
          if(visible(close)){close.click();return 'closed';}
          var labels=['collapse sidebar','expand sidebar','open sidebar','close sidebar','收起侧边栏','展开侧边栏'];
          var btns=document.querySelectorAll('button[aria-label]');
          for(var i=0;i<btns.length;i++){
            var a=(btns[i].getAttribute('aria-label')||'').toLowerCase();
            if(!visible(btns[i]))continue;
            for(var j=0;j<labels.length;j++){
              if(a.indexOf(labels[j])>=0){btns[i].click();return 'clicked';}
            }
          }
          return 'not-found';
        })()
    """.trimIndent()

    val CLOSE_SIDEBAR_JS: String = """
        (function(){
          function visible(el){return !!(el && (el.offsetWidth||el.offsetHeight||el.getClientRects().length));}
          var close=document.querySelector('.sessions-panel-mobile-close');
          if(visible(close)){close.click();return true;}
          var btns=document.querySelectorAll('button[aria-label]');
          for(var i=0;i<btns.length;i++){
            var a=(btns[i].getAttribute('aria-label')||'').toLowerCase();
            if(visible(btns[i]) && (a.indexOf('collapse sidebar')>=0 || a.indexOf('收起侧边栏')>=0)){
              btns[i].click();return true;
            }
          }
          return false;
        })()
    """.trimIndent()
}
