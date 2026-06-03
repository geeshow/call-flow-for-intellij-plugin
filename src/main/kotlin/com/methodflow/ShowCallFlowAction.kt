package com.methodflow

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.ui.Messages

/**
 * 에디터 커서 위치의 메소드를 기준으로 호출/피호출 관계도를 띄우는 액션.
 * 우클릭 메뉴(EditorPopupMenu) 또는 Ctrl+Alt+F 로 실행.
 */
class ShowCallFlowAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val psiFile = e.getData(CommonDataKeys.PSI_FILE) ?: return

        val method = runReadAction {
            val element = psiFile.findElementAt(editor.caretModel.offset) ?: return@runReadAction null
            CallGraphAnalyzer.enclosingCallable(element)
        }

        if (method == null) {
            Messages.showInfoMessage(project, "커서를 메소드(함수) 안에 둔 뒤 다시 실행해주세요.", "Call Flow")
            return
        }

        val result = CallGraphAnalyzer.analyze(project, method)
        CallFlowPanelService.getInstance(project)
            .show(result.baseId, result.graphJson, result.locations, result.edgeLocations)
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible =
            e.project != null && e.getData(CommonDataKeys.EDITOR) != null
    }
}
