package com.flux.codebuddy

/**
 * Client-side adaptation of the existing CodeBuddy web UI. Nothing is changed
 * on the server; all rules are injected into the WebView only.
 *
 * The overrides are intentionally narrow:
 *  - seed the existing theme/sidebar preferences on first run
 *  - force the web UI's own phone layout: on a phone-sized screen the desktop
 *    layout keeps a 360px session panel and leaves ~half the width for chat,
 *    so the viewport is narrowed to 760px, which switches the SPA to its
 *    mobile layout (full-width chat, drawer sidebar) at a ~1.1x zoom
 *  - shrink the type/spacing scale and compact the header, composer and login
 *    page on short landscape viewports, never on tablets/desktops
 *
 * If a future CodeBuddy release renames these classes the rules simply stop
 * applying; the web UI remains fully functional.
 */
object MobileInjection {

    private val CSS = """
        @media (orientation:landscape) and (max-height:560px){
          html{font-size:12px!important;}
          .app-status-bar{height:44px!important;min-height:44px!important;padding:2px 8px!important;}
          .app-status-bar button{height:26px!important;width:26px!important;}
          .composer-stack{padding:0 6px 6px!important;}
          .composer-card{gap:2px!important;padding-top:3px!important;border-radius:12px!important;}
          .composer-context-row{min-height:22px!important;padding:0 4px!important;}
          .composer-seat-chip{height:22px!important;min-height:22px!important;font-size:11px!important;padding:0 6px!important;}
          .composer-textarea{padding:0 8px!important;font-size:12px!important;min-height:18px!important;}
          .composer-toolbar{height:24px!important;min-height:24px!important;padding:0 6px 2px!important;}
          .composer-add,.composer-send{height:24px!important;min-height:24px!important;}
          .composer-send{width:24px!important;}
          .login-page{overflow:hidden!important;justify-content:flex-start!important;padding:4px 0 18px!important;}
          .login-content{gap:3px!important;padding:2px 0!important;}
          .login-logo.login-logo-pixel{width:40px!important;height:28px!important;}
          .login-title{font-size:15px!important;line-height:18px!important;}
          .login-tagline{font-size:10px!important;line-height:13px!important;}
          .login-hints{font-size:9px!important;line-height:1.25!important;margin-top:2px!important;}
          .login-hints p:not(:first-child){display:none!important;}
          .login-footer{position:absolute!important;bottom:2px!important;}
          .login-footer a{font-size:9px!important;line-height:12px!important;}
        }
    """.trimIndent()

    val DOCUMENT_START_SCRIPT: String = """
        (function(){
          // Phones only: switch the SPA to its mobile layout. The desktop layout
          // keeps a 360px session panel open, which leaves too little room for
          // chat on a landscape phone.
          (function(){
            var forced=false;
            function fixViewport(){
              try{
                var m=document.querySelector('meta[name="viewport"]');
                if(!m)return;
                if(!forced){
                  var device=Math.max(screen.width||0,screen.height||0);
                  if(device>1000||device<=767)return;
                  forced=true;
                }
                if(m.getAttribute('content')!=='width=760')m.setAttribute('content','width=760');
              }catch(e){}
            }
            fixViewport();
            document.addEventListener('DOMContentLoaded',fixViewport);
            window.addEventListener('load',fixViewport);
            var n=0,t=setInterval(function(){fixViewport();if(++n>60)clearInterval(t);},16);
            try{new MutationObserver(fixViewport).observe(document.documentElement,{childList:true,subtree:true});}catch(e){}
          })();
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
