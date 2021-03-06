package com.ypwang.plugin

import com.goide.psi.*
import com.goide.psi.impl.GoElementFactory
import com.goide.psi.impl.GoLiteralImpl
import com.goide.quickfix.GoRenameToQuickFix
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.LocalQuickFixOnPsiElement
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.impl.source.tree.PsiCommentImpl
import com.intellij.psi.impl.source.tree.PsiWhiteSpaceImpl
import com.ypwang.plugin.handler.*
import com.ypwang.plugin.model.LintIssue
import com.ypwang.plugin.quickfix.*

object DefaultHandler : ProblemHandler() {
    override fun doSuggestFix(file: PsiFile, document: Document, issue: LintIssue, overrideLine: Int): Pair<Array<LocalQuickFix>, TextRange?> = NonAvailableFix
}

object IneffAssignHandler : ProblemHandler() {
    override fun doSuggestFix(file: PsiFile, document: Document, issue: LintIssue, overrideLine: Int): Pair<Array<LocalQuickFix>, TextRange?> =
            chainFindAndHandle(file, document, issue, overrideLine) { element: GoReferenceExpression ->
                // get the variable
                val variable = issue.Text.substring(issue.Text.lastIndexOf(' ') + 1).trim('`')
                // normally cur pos is LeafPsiElement, parent should be GoVarDefinition (a := 1) or GoReferenceExpressImpl (a = 1)
                // we cannot delete/rename GoVarDefinition, as that would have surprising impact on usage below
                // while for Reference we could safely rename it to '_' without causing damage
                if (element.text == variable)
                    arrayOf<LocalQuickFix>(GoReferenceRenameToBlankQuickFix(element)) to element.identifier.textRange
                else NonAvailableFix
            }
}

object InterfacerHandler : ProblemHandler() {
    override fun doSuggestFix(file: PsiFile, document: Document, issue: LintIssue, overrideLine: Int): Pair<Array<LocalQuickFix>, TextRange?> =
            chainFindAndHandle(file, document, issue, overrideLine) { element: GoParameterDeclaration ->
                // last child is type signature
                arrayOf<LocalQuickFix>(GoReplaceParameterTypeFix(
                        issue.Text.substring(issue.Text.lastIndexOf(' ') + 1).trim('`'),
                        element
                )) to element.lastChild.textRange
            }
}

object WhitespaceHandler : ProblemHandler() {
    override fun doSuggestFix(file: PsiFile, document: Document, issue: LintIssue, overrideLine: Int): Pair<Array<LocalQuickFix>, TextRange?> {
        assert(issue.LineRange != null)

        val elements = mutableListOf<PsiElement>()
        val shift = overrideLine - issue.Pos.Line
        // whitespace linter tells us the start line and end line
        for (l in issue.LineRange!!.To downTo issue.LineRange.From) {
            val line = l + shift
            // line in document starts from 0
            val s = document.getLineStartOffset(line)
            val e = document.getLineEndOffset(line)
            if (s == e) {
                // whitespace line
                val element = file.findElementAt(s)
                if (element is PsiWhiteSpaceImpl && element.chars.all { it == '\n' })
                    elements.add(element)
            }
        }

        val start = document.getLineStartOffset(issue.LineRange.From + shift)
        val end = document.getLineEndOffset(issue.LineRange.To + shift)
        if (elements.isNotEmpty()) return arrayOf<LocalQuickFix>(GoDeleteElementsFix(elements, "Remove whitespace")) to TextRange(start, end)

        return NonAvailableFix
    }
}

object GoConstHandler : ProblemHandler() {
    override fun doSuggestFix(file: PsiFile, document: Document, issue: LintIssue, overrideLine: Int): Pair<Array<LocalQuickFix>, TextRange?> =
            chainFindAndHandle(file, document, issue, overrideLine) { element: GoStringLiteral ->
                arrayOf<LocalQuickFix>(GoIntroduceConstStringLiteralFix(file as GoFile, element.text)) to element.textRange
            }
}

object GoDotHandler : ProblemHandler() {
    override fun doSuggestFix(file: PsiFile, document: Document, issue: LintIssue, overrideLine: Int): Pair<Array<LocalQuickFix>, TextRange?> =
            chainFindAndHandle(file, document, issue, overrideLine) { element: PsiCommentImpl ->
                arrayOf<LocalQuickFix>(GoDotFix(element)) to element.textRange
            }
}

object TestPackageHandler : ProblemHandler() {
    override fun doSuggestFix(file: PsiFile, document: Document, issue: LintIssue, overrideLine: Int): Pair<Array<LocalQuickFix>, TextRange?> =
            chainFindAndHandle(file, document, issue, overrideLine) { element: GoPackageClause ->
                arrayOf<LocalQuickFix>(GoRenamePackageFix(element, element.identifier!!.text + "_test")) to element.identifier!!.textRange
            }
}

object GoPrintfFuncNameHandler : ProblemHandler() {
    override fun doSuggestFix(file: PsiFile, document: Document, issue: LintIssue, overrideLine: Int): Pair<Array<LocalQuickFix>, TextRange?> =
            chainFindAndHandle(file, document, issue, overrideLine) { element: GoFunctionOrMethodDeclaration ->
                arrayOf<LocalQuickFix>(GoRenameToQuickFix(element, "${element.identifier!!.text}f")) to element.identifier!!.textRange
            }
}

object ExhaustiveHandler : ProblemHandler() {
    override fun doSuggestFix(file: PsiFile, document: Document, issue: LintIssue, overrideLine: Int): Pair<Array<LocalQuickFix>, TextRange?> =
            chainFindAndHandle(file, document, issue, overrideLine) { element: GoExprSwitchStatement ->
                val fix = run {
                    if (element.exprCaseClauseList.last().default == null)
                        arrayOf<LocalQuickFix>(GoSwitchAddCaseFix(issue.Text.substring(issue.Text.indexOf(':')+2), element))
                    else
                        // last case is default, no need to add more cases
                        EmptyLocalQuickFix
                }

                fix to element.condition?.textRange
            }
}

object NlReturnHandler : ProblemHandler() {
    override fun doSuggestFix(file: PsiFile, document: Document, issue: LintIssue, overrideLine: Int): Pair<Array<LocalQuickFix>, TextRange?> {
        val element = file.findElementAt(document.getLineStartOffset(overrideLine))
        return if (element is PsiWhiteSpace) {
            arrayOf<LocalQuickFix>(object: LocalQuickFixOnPsiElement(element){
                override fun getFamilyName(): String = text
                override fun getText(): String = "Insert new line before"
                override fun invoke(project: Project, file: PsiFile, startElement: PsiElement, endElement: PsiElement) {
                    startElement.parent.addBefore(GoElementFactory.createNewLine(project, 2), startElement)
                }
            }) to file.findElementAt(calcPos(document, issue, overrideLine))?.textRange
        } else NonAvailableFix
    }
}

// direct to explanation
object ScopelintHandler : ProblemHandler() {
    override fun doSuggestFix(file: PsiFile, document: Document, issue: LintIssue, overrideLine: Int): Pair<Array<LocalQuickFix>, TextRange?> =
            arrayOf<LocalQuickFix>(GoBringToExplanationFix("https://github.com/xxpxxxxp/intellij-plugin-golangci-lint/blob/master/explanation/scopelint.md")) to null
}

object GoErr113Handler : ProblemHandler() {
    override fun doSuggestFix(file: PsiFile, document: Document, issue: LintIssue, overrideLine: Int): Pair<Array<LocalQuickFix>, TextRange?> =
            arrayOf<LocalQuickFix>(GoBringToExplanationFix("https://github.com/xxpxxxxp/intellij-plugin-golangci-lint/blob/master/explanation/goerr113.md")) to null
}

object GoFumptHandler : ProblemHandler() {
    override fun doSuggestFix(file: PsiFile, document: Document, issue: LintIssue, overrideLine: Int): Pair<Array<LocalQuickFix>, TextRange?> =
            arrayOf<LocalQuickFix>(GoBringToExplanationFix("https://github.com/xxpxxxxp/intellij-plugin-golangci-lint/blob/master/explanation/gofumpt.md")) to null
}

object ExportLoopRefHandler : ProblemHandler() {
    override fun doSuggestFix(file: PsiFile, document: Document, issue: LintIssue, overrideLine: Int): Pair<Array<LocalQuickFix>, TextRange?> =
            arrayOf<LocalQuickFix>(GoBringToExplanationFix("https://github.com/xxpxxxxp/intellij-plugin-golangci-lint/blob/master/explanation/exportloopref.md")) to null
}

object NoCtxRefHandler : ProblemHandler() {
    override fun doSuggestFix(file: PsiFile, document: Document, issue: LintIssue, overrideLine: Int): Pair<Array<LocalQuickFix>, TextRange?> =
            arrayOf<LocalQuickFix>(GoBringToExplanationFix("https://github.com/sonatard/noctx/blob/master/README.md")) to null
}

// just fit range
object DuplHandler : ProblemHandler() {
    override fun doSuggestFix(file: PsiFile, document: Document, issue: LintIssue, overrideLine: Int): Pair<Array<LocalQuickFix>, TextRange?> {
        assert(issue.LineRange != null)
        // to avoid annoying, just show the first line
        val start = document.getLineStartOffset(overrideLine)
        val end = document.getLineEndOffset(overrideLine)
        return EmptyLocalQuickFix to TextRange(start, end)
    }
}

object UnparamHandler : ProblemHandler() {
    override fun doSuggestFix(file: PsiFile, document: Document, issue: LintIssue, overrideLine: Int): Pair<Array<LocalQuickFix>, TextRange?> =
            // intellij will report same issue and provides fix, just fit the range
            chainFindAndHandle(file, document, issue, overrideLine) { element: GoParameterDeclaration ->
                var psiElement: PsiElement? = element
                while (psiElement != null && psiElement !is GoFunctionOrMethodDeclaration)
                    psiElement = psiElement.parent

                val fix = if (psiElement is GoFunctionOrMethodDeclaration) arrayOf<LocalQuickFix>(NoLintFuncCommentFix("unparam", psiElement)) else EmptyLocalQuickFix
                fix to element.textRange
            }
}

object GoMndHandler : ProblemHandler() {
    override fun doSuggestFix(file: PsiFile, document: Document, issue: LintIssue, overrideLine: Int): Pair<Array<LocalQuickFix>, TextRange?> =
            chainFindAndHandle(file, document, issue, overrideLine) { element: GoLiteralImpl ->
                EmptyLocalQuickFix to element.textRange
            }
}

// func nolint
private fun funcNoLintHandler(linter: String): ProblemHandler = object : ProblemHandler() {
    override fun doSuggestFix(file: PsiFile, document: Document, issue: LintIssue, overrideLine: Int): Pair<Array<LocalQuickFix>, TextRange?> =
            chainFindAndHandle(file, document, issue, overrideLine) { element: GoFunctionOrMethodDeclaration ->
                arrayOf<LocalQuickFix>(NoLintFuncCommentFix(linter, element)) to null
            }
}

// attempt to suggest auto-fix, if possible, clarify affected PsiElement for better inspection
val quickFixHandler: Map<String, ProblemHandler> = mutableMapOf(
        "ineffassign" to IneffAssignHandler,
        "scopelint" to ScopelintHandler,
        "gocritic" to GoCriticHandler,
        "interfacer" to InterfacerHandler,
        "whitespace" to WhitespaceHandler,
        "golint" to GolintHandler,
        "goconst" to GoConstHandler,
        "maligned" to MalignedHandler,
        "unparam" to UnparamHandler,
        "dupl" to DuplHandler,
        "godot" to GoDotHandler,
        "testpackage" to TestPackageHandler,
        "goerr113" to GoErr113Handler,
        "stylecheck" to StyleCheckHandler,
        "gomnd" to GoMndHandler,
        "staticcheck" to StaticCheckHandler,
        "goprintffuncname" to GoPrintfFuncNameHandler,
//        "exhaustive" to ExhaustiveHandler,    // exhaustive is error prone
        "gosimple" to GoSimpleHandler,
        "gofumpt" to GoFumptHandler,
        "exportloopref" to ExportLoopRefHandler,
        "noctx" to NoCtxRefHandler,
        "nlreturn" to NlReturnHandler
    ).apply {
        this.putAll(listOf("structcheck", "varcheck", "deadcode", "unused").map { it to NamedElementHandler })
        this.putAll(ProblemHandler.FuncLinters.map { it to funcNoLintHandler(it) })
    }