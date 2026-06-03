package com.methodflow

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandlerAdapter
import javax.swing.JComponent

/**
 * "Call Flow" 툴윈도우. JCEF(내장 Chromium) 로 그래프 HTML 을 렌더링하고,
 * 노드 클릭 → window.__navigate(id) → JBCefJSQuery 브리지 → 소스 이동을 연결한다.
 */
class CallFlowToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val service = CallFlowPanelService.getInstance(project)

        val component: JComponent = if (JBCefApp.isSupported()) {
            val browser = JBCefBrowser()

            // 브리지 1: 소스 이동 (fire-and-forget). "caller=>callee" 면 호출 위치, 아니면 선언으로
            val navQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)
            navQuery.addHandler { msg ->
                if (msg.contains("=>")) service.navigateToCall(msg) else service.navigateTo(msg)
                null
            }

            // 브리지 2: 무한 드릴다운 (요청-응답). req="nodeId|direction" → 그래프 fragment JSON 반환
            val expandQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)
            expandQuery.addHandler { req ->
                JBCefJSQuery.Response(service.expandNode(req) ?: "")
            }

            // HTML 은 분석마다 새로 loadHTML 되므로, 매 로드 후 브리지 함수를 재정의
            browser.jbCefClient.addLoadHandler(object : CefLoadHandlerAdapter() {
                override fun onLoadEnd(cef: CefBrowser, frame: CefFrame, httpStatusCode: Int) {
                    val send = navQuery.inject("__m")
                    val expandInject = expandQuery.inject(
                        "__req",
                        "if(window.__expandResolve)window.__expandResolve(response)",
                        "if(window.__expandResolve)window.__expandResolve(null)"
                    )
                    cef.executeJavaScript(
                        "window.__send = function(__m){ $send };" +
                            "window.__navigate = function(id){ window.__send(id); };" +
                            "window.__navigateCall = function(a,b){ window.__send(a + '=>' + b); };" +
                            "window.__expand = function(req, cb){" +
                            "  window.__expandResolve = function(r){ window.__expandResolve = null; if(cb) cb(r); };" +
                            "  var __req = req; $expandInject };",
                        cef.url, 0
                    )
                }
            }, browser.cefBrowser)

            service.registerBrowser(browser)
            browser.component
        } else {
            JBLabel("이 IDE 런타임은 JCEF(내장 브라우저)를 지원하지 않습니다. " +
                    "Help > Find Action > 'Choose Boot Java Runtime' 에서 JCEF 포함 런타임을 선택하세요.")
        }

        val content = ContentFactory.getInstance().createContent(component, "", false)
        toolWindow.contentManager.addContent(content)
    }

    override fun shouldBeAvailable(project: Project): Boolean = true
}
