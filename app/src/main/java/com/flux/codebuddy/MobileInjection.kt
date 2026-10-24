package com.flux.codebuddy

/**
 * Client-side adaptation of the existing CodeBuddy web UI. Nothing is changed
 * on the server; all rules are injected into the WebView only.
 *
 * The overrides are intentionally narrow:
 *  - force the existing dark theme (the native shell is dark)
 *  - seed the existing sidebar as collapsed on first run, at a narrow width
 *  - compact the existing header/composer and login page only on short
 *    landscape viewports (phones), never on tablets/desktops
 *
 * If a future CodeBuddy release renames these classes the rules simply stop
 * applying; the web UI remains fully functional.
 */
object MobileInjection {

    private val CSS = """
        @media (orientation:landscape) and (max-height:520px){
          .app-status-bar{height:40px!important;min-height:40px!important;padding:0 10px!important;}
          .app-status-bar span.font-medium{font-size:12px!important;line-height:14px!important;}
          .composer-stack{padding:0 8px 8px!important;}
          .composer-card{gap:4px!important;padding-top:6px!important;border-radius:14px!important;}
          .composer-context-row{min-height:22px!important;padding:0 6px!important;}
          .composer-textarea{padding:2px 10px 0 12px!important;font-size:13px!important;}
          .composer-toolbar{height:32px!important;min-height:32px!important;}
          .composer-chip,.composer-add,.composer-send{height:32px!important;min-height:32px!important;}
          .composer-send{width:32px!important;}
          .login-page{overflow-y:auto!important;align-items:flex-start!important;padding:8px 0 26px!important;}
          .login-content{gap:6px!important;padding:6px 0!important;}
          .login-logo.login-logo-pixel{width:56px!important;height:40px!important;}
          .login-title{font-size:17px!important;line-height:21px!important;}
          .login-tagline{font-size:11px!important;line-height:15px!important;}
          .login-hints{font-size:9px!important;line-height:1.3!important;max-height:78px!important;overflow-y:auto!important;margin-top:4px!important;}
          .login-hints p{margin:2px 0!important;}
          .login-footer{bottom:4px!important;}
          .login-footer a{font-size:10px!important;line-height:14px!important;}
        }
    """.trimIndent()

    val DOCUMENT_START_SCRIPT: String = """
        (function(){
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
