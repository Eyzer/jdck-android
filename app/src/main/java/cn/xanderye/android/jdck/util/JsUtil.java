package cn.xanderye.android.jdck.util;

/**
 * 注入到京东登录页的脚本，Activity 与后台 Service 共用一份
 */
public class JsUtil {

    /**
     * 运营商号码认证页自动勾选协议并点击一键登录。
     * 号码由运营商网关下发，无需填写；页面未渲染出来时返回 no，由 Java 侧重试。
     */
    private static final String ONE_KEY_JS =
            "(function(){"
            + "var docs=[document];"
            + "try{for(var f=0;f<window.frames.length;f++){try{if(window.frames[f].document)docs.push(window.frames[f].document);}catch(e){}}}catch(e){}"
            + "var now=Date.now(),hit=0;"
            + "for(var i=0;i<docs.length;i++){"
            + "var doc=docs[i],b=doc.body;if(!b)continue;"
            + "var t=b.innerText||'';"
            + "if(t.indexOf('号码认证')<0&&t.indexOf('一键登录')<0)continue;"
            + "var c=doc.getElementsByClassName('policy_tip-checkbox')[0];"
            + "var btn=pk(doc,'一键登录')||pk(doc,'本机号码登录');"
            + "if(!c&&!btn)continue;"
            + "hit=1;"
            + "if(window.__jdckT&&now-window.__jdckT<3000)continue;"
            + "if(c&&!/checked/.test(c.className)&&c.getAttribute('aria-checked')!=='true')c.click();"
            + "(function(dd){setTimeout(function(){var x=pk(dd,'一键登录')||pk(dd,'本机号码登录');if(x){fillMask(dd);fire(dd,x);}},400);})(doc);"
            + "}"
            + "if(hit)window.__jdckT=now;"
            + "return hit?'ok':'no';"
            + "function vis(e){return e&&e.offsetParent!==null;}"
            + "function pk(doc,kw){"
            + "var n=doc.querySelectorAll('button,a,div,span,p'),best=null;"
            + "for(var i=0;i<n.length;i++){var e=n[i];if(!vis(e))continue;var s=(e.innerText||'').trim();"
            + "if(!s||s.length>30||s.indexOf(kw)<0)continue;"
            + "if(e.children.length===0)return e;if(!best)best=e;}"
            + "return best;}"
            + "function fire(doc,x){"
            + "var b=doc.body,p=x;"
            + "while(p&&p!==b){var cs=getComputedStyle(p);if(cs.cursor==='pointer'||p.tagName==='BUTTON'||p.tagName==='A')break;p=p.parentElement;}"
            + "if(!p||p===b)p=x;"
            + "p.click();}"
            + "function fillMask(doc){"
            + "var ph=window.__jdckPhone;if(!ph||ph.length<11)return;"
            + "var n=doc.querySelectorAll('input'),bx=[];"
            + "for(var i=0;i<n.length;i++){var e=n[i];if(e.offsetParent===null)continue;"
            + "var t=(e.getAttribute('type')||'').toLowerCase();"
            + "if(e.getAttribute('maxlength')==='1'||t==='tel'||t==='number')bx.push(e);}"
            + "if(bx.length!==4)return;"
            + "var m=ph.substr(3,4);"
            + "for(var j=0;j<4;j++){bx[j].value=m.charAt(j);bx[j].dispatchEvent(new Event('input',{bubbles:true}));}}"
            + "})()";

    /**
     * 一键登录脚本带上选中的号码，用于补全页面上的掩码输入格
     */
    public static String oneKeyJs(String phone) {
        return "var __jdckPhone='" + digits(phone) + "';" + ONE_KEY_JS;
    }

    /**
     * 登录页填号脚本，账号列表里选中的号码自动补全到输入框
     *
     * @param selected 形如 "18888888888 123456"，空格后为密码，可省略
     */
    public static String fillLoginJs(String selected) {
        String account = "";
        String password = "";
        if (selected != null && !selected.trim().isEmpty()) {
            String[] info = selected.trim().split(" ");
            account = info[0];
            if (info.length > 1) {
                password = info[1];
            }
        }
        StringBuilder js = new StringBuilder();
        js.append("var account='").append(escape(account)).append("';");
        js.append("var evt=new InputEvent('input',{inputType:'insertText',data:account,dataTransfer:null,isComposing:false});");
        js.append("var cb=document.getElementsByClassName('policy_tip-checkbox')[0];");
        js.append("if(cb&&!/checked/.test(cb.className))cb.click();");
        js.append("var u=document.getElementById('username');if(u){u.value=account;u.dispatchEvent(evt);}");
        if (account.matches("1\\d{10}")) {
            js.append("var m=document.getElementsByClassName('acc-input mobile J_ping')[0];if(m){m.value=account;m.dispatchEvent(evt);}");
        }
        if (!password.isEmpty()) {
            js.append("var pwd='").append(escape(password)).append("';");
            js.append("var evt2=new InputEvent('input',{inputType:'insertText',data:pwd,dataTransfer:null,isComposing:false});");
            js.append("var p=document.getElementById('pwd');if(p){p.value=pwd;p.dispatchEvent(evt2);}");
            js.append("var b=document.querySelector('#app>div>a');if(b)b.click();");
        }
        return js.toString();
    }

    /**
     * 判断当前页面状态：normal / captcha（京东风控验证）/ blank
     */
    public static String pageStateJs() {
        return "(function(){var b=document.body;if(!b)return 'blank';var t=b.innerText||'';"
                + "if(t.indexOf('安全验证')>=0||t.indexOf('滑块验证')>=0||t.indexOf('拖动滑块')>=0"
                + "||t.indexOf('请完成验证')>=0||t.indexOf('验证码登录')>=0)return 'captcha';"
                + "return 'normal';})()";
    }

    private static String digits(String s) {
        if (s == null) {
            return "";
        }
        return s.replaceAll("[^0-9]", "");
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("\\", "").replace("'", "");
    }
}
