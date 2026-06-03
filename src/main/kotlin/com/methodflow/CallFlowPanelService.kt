package com.methodflow

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.ui.jcef.JBCefBrowser

/**
 * 분석 결과(JSON)를 JCEF 웹뷰에 주입하고, 노드 클릭 시 소스로 점프시키는 프로젝트 서비스.
 * 액션 → 서비스.show() → 툴윈도우 활성화 → HTML 로드.
 * 웹뷰 클릭 → __navigate(id) → 브리지 → 서비스.navigateTo(id) → 에디터 이동.
 */
@Service(Service.Level.PROJECT)
class CallFlowPanelService(private val project: Project) {

    @Volatile
    var browser: JBCefBrowser? = null
        private set

    @Volatile
    private var pending: Pair<String, String>? = null   // baseId, graphJson

    // 노드 id -> 선언 위치 / "callerId=>calleeId" -> 호출(call-site) 위치 (드릴다운으로 누적되므로 mutable)
    private val locations = java.util.concurrent.ConcurrentHashMap<String, CallGraphAnalyzer.Loc>()
    private val edgeLocations = java.util.concurrent.ConcurrentHashMap<String, CallGraphAnalyzer.Loc>()

    /** 액션에서 호출: 데이터 저장 후 툴윈도우를 띄우고 flush. */
    fun show(
        baseId: String,
        graphJson: String,
        locations: Map<String, CallGraphAnalyzer.Loc>,
        edgeLocations: Map<String, CallGraphAnalyzer.Loc>
    ) {
        pending = baseId to graphJson
        // 새 분석마다 위치 맵 초기화 후 재구성
        this.locations.clear(); this.locations.putAll(locations)
        this.edgeLocations.clear(); this.edgeLocations.putAll(edgeLocations)
        val tw = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID)
        if (tw != null) {
            tw.activate({ flush() }, true)
        } else {
            flush()
        }
    }

    /** 툴윈도우 팩토리에서 브라우저 생성 직후 등록. */
    fun registerBrowser(browser: JBCefBrowser) {
        this.browser = browser
        flush()
    }

    /** 노드 클릭 → 메소드 선언으로 이동. */
    fun navigateTo(nodeId: String) = navigate(locations[nodeId])

    /** 호출선 클릭 → 실제 호출 위치(call-site)로 이동. key = "callerId=>calleeId" */
    fun navigateToCall(edgeKey: String) {
        // call-site 가 없으면 callee 선언으로 폴백
        val loc = edgeLocations[edgeKey] ?: locations[edgeKey.substringAfterLast("=>")]
        navigate(loc)
    }

    private fun navigate(loc: CallGraphAnalyzer.Loc?) {
        loc ?: return
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed || !loc.file.isValid) return@invokeLater
            OpenFileDescriptor(project, loc.file, loc.offset).navigate(true)
        }
    }

    /**
     * 무한 드릴다운: nodeId 를 PSI 로 되찾아 한 방향(callee/caller)으로 한 단계 더 분석한 뒤,
     * 위치 맵을 누적 병합하고 그래프 fragment(JSON)를 반환한다. req = "nodeId|direction"
     */
    fun expandNode(req: String): String? {
        val sep = req.lastIndexOf('|')
        if (sep < 0) return null
        val nodeId = req.substring(0, sep)
        val direction = req.substring(sep + 1)

        val element = runReadAction { resolveElement(nodeId) } ?: return null
        val result = CallGraphAnalyzer.expandFrom(project, element, direction)
        locations.putAll(result.locations)
        edgeLocations.putAll(result.edgeLocations)
        return result.graphJson
    }

    /** 저장된 선언 위치로 해당 메소드 PSI 요소를 다시 찾는다. (read action 내에서 호출) */
    private fun resolveElement(nodeId: String): PsiElement? {
        val loc = locations[nodeId] ?: return null
        if (!loc.file.isValid) return null
        val psiFile = PsiManager.getInstance(project).findFile(loc.file) ?: return null
        val at = psiFile.findElementAt(loc.offset) ?: return null
        return CallGraphAnalyzer.enclosingCallable(at)
    }

    private fun flush() {
        val b = browser ?: return
        val (baseId, json) = pending ?: return
        b.loadHTML(renderHtml(baseId, json))
    }

    private fun renderHtml(baseId: String, graphJson: String): String {
        val tpl = javaClass.getResource("/web/callflow.html")?.readText()
            ?: return "<html><body>callflow.html 리소스를 찾을 수 없습니다.</body></html>"
        return tpl
            .replace("/*__GRAPH__*/{}", graphJson)
            .replace("/*__BASE__*/\"\"", "\"" + baseId.replace("\"", "\\\"") + "\"")
    }

    companion object {
        const val TOOL_WINDOW_ID = "Call Flow"
        fun getInstance(project: Project): CallFlowPanelService = project.service()
    }
}
