package com.methodflow

import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.psiUtil.containingClassOrObject

/**
 * 경량 PSI 기반 호출 그래프 분석기.
 *
 *  - callee(이 메소드가 호출): 본문의 호출식을 PSI 로 수집 → reference.resolve() 로 선언을 찾음
 *    (전체 타입 추론은 하지 않음 = "경량 휴리스틱". 해석 실패시 이름만 사용)
 *  - caller(이 메소드를 호출): ReferencesSearch 로 IDE 인덱스를 그대로 활용
 *
 * 기준 메소드에서 양방향으로 [MAX_DEPTH] 단계까지 BFS 하여 그래프를 만든다.
 */
object CallGraphAnalyzer {

    private const val MAX_DEPTH = 2      // 각 방향(호출/피호출) 확장 깊이
    private const val MAX_CHILDREN = 14  // 노드당 표시할 최대 가지 수

    class Node(val id: String, val cls: String, val type: String) {
        val calls = LinkedHashSet<String>()   // "A.b" 형태의 callee id 목록 (A 가 b 를 호출)
    }

    /** 노드 클릭 시 점프할 소스 위치. */
    data class Loc(val file: VirtualFile, val offset: Int)

    data class Result(
        val baseId: String,
        val graphJson: String,
        val locations: Map<String, Loc>,        // 노드 id -> 선언 위치
        val edgeLocations: Map<String, Loc>      // "callerId=>calleeId" -> 실제 호출(call-site) 위치
    )

    /** 호출선 키: caller 가 callee 를 호출하는 edge. */
    private fun edgeKey(callerId: String, calleeId: String) = "$callerId=>$calleeId"

    fun analyze(project: Project, base: PsiElement): Result = runReadAction {
        val ctx = Ctx()
        val baseId = idOf(base) ?: "unknown"

        nodeFor(base, ctx)
        expandCallees(base, ctx, 0, HashSet())
        expandCallers(project, base, ctx, 0, HashSet())

        Result(baseId, toJson(ctx.nodes, baseId), ctx.locations, ctx.edges)
    }

    /** 분석 중 누적되는 상태. */
    private class Ctx {
        val nodes = LinkedHashMap<String, Node>()
        val locations = LinkedHashMap<String, Loc>()
        val edges = LinkedHashMap<String, Loc>()
    }

    // ---------- 그래프 확장 ----------

    private fun expandCallees(el: PsiElement, ctx: Ctx, depth: Int, seen: MutableSet<String>) {
        if (depth >= MAX_DEPTH) return
        val parent = nodeFor(el, ctx) ?: return
        if (!seen.add(parent.id)) return

        calleesOf(el).take(MAX_CHILDREN).forEach { (site, target) ->
            val child = nodeFor(target, ctx) ?: return@forEach
            if (child.id == parent.id) return@forEach
            parent.calls.add(child.id)
            recordEdge(parent.id, child.id, site, ctx)   // 호출 위치: parent 본문 내 site
            expandCallees(target, ctx, depth + 1, seen)
        }
    }

    private fun expandCallers(project: Project, el: PsiElement, ctx: Ctx, depth: Int, seen: MutableSet<String>) {
        if (depth >= MAX_DEPTH) return
        val target = nodeFor(el, ctx) ?: return
        if (!seen.add(target.id)) return

        val scope = GlobalSearchScope.projectScope(project)
        // 호출자별 첫 호출 위치(call-site) 보존
        val callerSites = LinkedHashMap<PsiElement, PsiElement>()
        ReferencesSearch.search(el, scope).findAll().forEach { ref ->
            val caller = enclosingCallable(ref.element) ?: return@forEach
            callerSites.putIfAbsent(caller, ref.element)
        }

        callerSites.entries.take(MAX_CHILDREN).forEach { (caller, site) ->
            val cn = nodeFor(caller, ctx) ?: return@forEach
            if (cn.id == target.id) return@forEach
            cn.calls.add(target.id)                       // caller 가 target 을 호출
            recordEdge(cn.id, target.id, site, ctx)       // 호출 위치: caller 본문 내 site
            expandCallers(project, caller, ctx, depth + 1, seen)
        }
    }

    private fun recordEdge(callerId: String, calleeId: String, site: PsiElement, ctx: Ctx) {
        val key = edgeKey(callerId, calleeId)
        if (key in ctx.edges) return
        val file = site.containingFile?.virtualFile ?: return
        ctx.edges[key] = Loc(file, site.textOffset)
    }

    // ---------- callee 수집: (호출위치, 선언) 쌍 ----------

    private fun calleesOf(el: PsiElement): List<Pair<PsiElement, PsiElement>> {
        // 선언별 첫 호출식만 유지 (distinct callee, MAX_CHILDREN 의미 보존)
        val byTarget = LinkedHashMap<PsiElement, PsiElement>()   // declaration -> callSite
        when (el) {
            is KtNamedFunction -> {
                val body = el.bodyExpression ?: return emptyList()
                PsiTreeUtil.collectElementsOfType(body, KtCallExpression::class.java).forEach { call ->
                    val callee = call.calleeExpression as? KtNameReferenceExpression ?: return@forEach
                    val resolved = runCatching { callee.mainReference.resolve() }.getOrNull()
                    if (resolved is KtNamedFunction || resolved is PsiMethod) byTarget.putIfAbsent(resolved, callee)
                }
            }
            is PsiMethod -> {
                val body = el.body ?: return emptyList()
                PsiTreeUtil.collectElementsOfType(body, PsiMethodCallExpression::class.java).forEach { call ->
                    val resolved = runCatching { call.resolveMethod() }.getOrNull()
                    if (resolved != null) byTarget.putIfAbsent(resolved, call.methodExpression)
                }
            }
        }
        return byTarget.map { (target, site) -> site to target }
    }

    // ---------- 식별/분류 헬퍼 ----------

    /** 호출 참조가 속한 메소드(KtNamedFunction / PsiMethod) 를 찾는다. */
    fun enclosingCallable(element: PsiElement): PsiElement? =
        PsiTreeUtil.getParentOfType(element, KtNamedFunction::class.java)
            ?: PsiTreeUtil.getParentOfType(element, PsiMethod::class.java)

    /** "ClassName.methodName" 형태의 안정적 id. */
    private fun idOf(el: PsiElement): String? = when (el) {
        is KtNamedFunction -> {
            val cls = el.containingClassOrObject?.name
                ?: el.containingKtFile.name.removeSuffix(".kt") + "Kt"
            el.name?.let { "$cls.$it" }
        }
        is PsiMethod -> {
            val cls = el.containingClass?.name ?: "?"
            "$cls.${el.name}"
        }
        else -> null
    }

    private fun nodeFor(el: PsiElement, ctx: Ctx): Node? {
        val id = idOf(el) ?: return null
        // 소스 위치 기록 (선언 이름 위치 우선)
        if (id !in ctx.locations) {
            val file = el.containingFile?.virtualFile
            if (file != null) ctx.locations[id] = Loc(file, navOffset(el))
        }
        return ctx.nodes.getOrPut(id) {
            val cls = id.substringBeforeLast('.')
            Node(id, cls, typeOf(cls))
        }
    }

    /** 선언의 이름 식별자 위치(없으면 요소 시작) 오프셋. */
    private fun navOffset(el: PsiElement): Int {
        val nameOffset = (el as? PsiNameIdentifierOwner)?.nameIdentifier?.textOffset
        return nameOffset ?: el.textOffset
    }

    /** 클래스명 규칙으로 노드 색상 타입 추정. */
    private fun typeOf(cls: String): String = when {
        cls.endsWith("Controller") || cls.endsWith("Client") ||
            cls.endsWith("Gateway") || cls.endsWith("Consumer") ||
            cls.endsWith("Listener") || cls.endsWith("Publisher") -> "api"
        cls.endsWith("Service") || cls.endsWith("Syncer") ||
            cls.endsWith("Manager") || cls.endsWith("Handler") -> "svc"
        cls.endsWith("Repository") || cls.endsWith("Repo") ||
            cls.endsWith("Dao") || cls.endsWith("Entity") -> "repo"
        else -> "util"
    }

    // ---------- 직렬화 ----------

    private fun toJson(nodes: Map<String, Node>, baseId: String): String {
        val sb = StringBuilder("{")
        var first = true
        for (n in nodes.values) {
            if (!first) sb.append(",")
            first = false
            sb.append('"').append(esc(n.id)).append("\":{")
            sb.append("\"cls\":\"").append(esc(n.cls)).append("\",")
            sb.append("\"type\":\"").append(n.type).append('"')
            if (n.id == baseId) sb.append(",\"root\":true")
            sb.append(",\"calls\":[")
            var f2 = true
            for (c in n.calls) {
                if (!f2) sb.append(",")
                f2 = false
                sb.append('"').append(esc(c)).append('"')
            }
            sb.append("]}")
        }
        sb.append("}")
        return sb.toString()
    }

    private fun esc(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"")
}
