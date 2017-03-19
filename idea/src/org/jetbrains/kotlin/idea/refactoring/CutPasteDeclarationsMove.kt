/*
 * Copyright 2010-2017 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.idea.refactoring

import com.intellij.codeInsight.editorActions.CopyPastePostProcessor
import com.intellij.codeInsight.editorActions.TextBlockTransferableData
import com.intellij.codeInsight.hint.HintManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.RangeMarker
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Ref
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.*
import org.jetbrains.annotations.TestOnly
import org.jetbrains.kotlin.idea.caches.resolve.resolveToDescriptor
import org.jetbrains.kotlin.idea.codeInsight.shorten.runRefactoringAndKeepDelayedRequests
import org.jetbrains.kotlin.idea.refactoring.move.moveDeclarations.*
import org.jetbrains.kotlin.idea.util.IdeDescriptorRenderers
import org.jetbrains.kotlin.idea.util.application.executeWriteCommand
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.psiUtil.elementsInRange
import org.jetbrains.kotlin.psi.psiUtil.startOffset
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable

//TODO: how to make it work inside same project only?
class KotlinDeclarationsTransferableData(
        val sourceFileUrl: String,
        val stubTexts: List<String>,
        val declarationNames: Set<String>
) : TextBlockTransferableData {

    override fun getFlavor() = DATA_FLAVOR
    override fun getOffsetCount() = 0

    override fun getOffsets(offsets: IntArray?, index: Int) = index
    override fun setOffsets(offsets: IntArray?, index: Int) = index

    companion object {
        val DATA_FLAVOR = DataFlavor(MoveDeclarationsCopyPasteProcessor::class.java, "class: MoveDeclarationsCopyPasteProcessor")
    }
}

class MoveDeclarationsCopyPasteProcessor : CopyPastePostProcessor<KotlinDeclarationsTransferableData>() {
    companion object {
        private val LOG = Logger.getInstance(MoveDeclarationsCopyPasteProcessor::class.java)

        @TestOnly var ACTIVATE_IN_TEST_MODE = false
        @TestOnly var REFACTORING_PERFORMED = false

        private val STUB_RENDERER = IdeDescriptorRenderers.SOURCE_CODE.withOptions {
            defaultParameterValueRenderer = { "xxx" } // we need default value to be parsed as expression
        }
    }

    override fun collectTransferableData(
            file: PsiFile,
            editor: Editor,
            startOffsets: IntArray,
            endOffsets: IntArray
    ): List<KotlinDeclarationsTransferableData> {
        if (file !is KtFile) return emptyList()
        if (startOffsets.size != 1) return emptyList()

        val declarations = rangeToDeclarations(file, startOffsets[0], endOffsets[0])
        if (declarations.isEmpty() || declarations.any { it.parent !is KtFile }) return emptyList()
        
        if (declarations.any { it.name == null }) return emptyList()
        val declarationNames = declarations.map { it.name!! }.toSet()

        val stubTexts = declarations.map { STUB_RENDERER.render(it.resolveToDescriptor()) }
        return listOf(KotlinDeclarationsTransferableData(file.virtualFile.url, stubTexts, declarationNames))
    }

    override fun extractTransferableData(content: Transferable): List<KotlinDeclarationsTransferableData> {
        try {
            if (content.isDataFlavorSupported(KotlinDeclarationsTransferableData.DATA_FLAVOR)) {
                return listOf(content.getTransferData(KotlinDeclarationsTransferableData.DATA_FLAVOR) as KotlinDeclarationsTransferableData)
            }
        }
        catch (e: Throwable) {
            LOG.error(e)
        }
        return emptyList()
    }

    override fun processTransferableData(
            project: Project,
            editor: Editor,
            bounds: RangeMarker,
            caretOffset: Int,
            indented: Ref<Boolean>,
            values: List<KotlinDeclarationsTransferableData>
    ) {
        REFACTORING_PERFORMED = false
        if (DumbService.getInstance(project).isDumb) return

        val data = values.single()
        val sourceFileUrl = data.sourceFileUrl
        val sourceFile = VirtualFileManager.getInstance().findFileByUrl(sourceFileUrl) ?: return
        //TODO: check file is inside project

        val psiDocumentManager = PsiDocumentManager.getInstance(project)
        psiDocumentManager.commitAllDocuments()

        val targetPsiFile = psiDocumentManager.getPsiFile(editor.document) as? KtFile ?: return
        val sourcePsiFile = PsiManager.getInstance(project).findFile(sourceFile) as? KtFile ?: return
        if (targetPsiFile == sourcePsiFile) return

        val declarations = rangeToDeclarations(targetPsiFile, bounds.startOffset, bounds.endOffset)
        if (declarations.isEmpty() || declarations.any { it.parent !is KtFile }) return

        if (sourcePsiFile.packageFqName == targetPsiFile.packageFqName) return

        // check that declarations were cut (not copied)
        val filteredDeclarations = sourcePsiFile.declarations.filter { it.name in data.declarationNames }
        val stubs = data.stubTexts.toSet()
        if (filteredDeclarations.any { STUB_RENDERER.render(it.resolveToDescriptor()) in stubs }) return

        fun doRefactoring(): Boolean {
            psiDocumentManager.commitAllDocuments()
            //TODO: check validity of everything

            Processor(project, sourcePsiFile, targetPsiFile, declarations, data.stubTexts).performRefactoring()
            return true
        }

        if (ApplicationManager.getApplication().isUnitTestMode) {
            if (ACTIVATE_IN_TEST_MODE) {
                REFACTORING_PERFORMED = doRefactoring()
            }
            return
        }

        if (HintManager.getInstance().hasShownHintsThatWillHideByOtherHint(true)) return

        //TODO: make it available after Esc
        ApplicationManager.getApplication().invokeLater {
            HintManager.getInstance().showQuestionHint(
                    editor, "Update usages to reflect package name change?", bounds.startOffset, bounds.endOffset, ::doRefactoring)
        }
    }

    private class Processor(
            private val project: Project,
            private val sourcePsiFile: KtFile,
            private val targetPsiFile: KtFile,
            private val pastedDeclarations: List<KtNamedDeclaration>,
            private val stubTexts: List<String>
    ) {
        private val psiDocumentManager = PsiDocumentManager.getInstance(project)
        private val sourceDocument = psiDocumentManager.getDocument(sourcePsiFile)!!

        fun performRefactoring() {
            val commandName = "Usage update"
            val commandGroupId = Any()

            val insertedRangeMarker = project.executeWriteCommand<RangeMarker>(commandName, commandGroupId) {
                //TODO: can stub declarations interfere with pasted declarations? I could not find such cases
                insertStubDeclarations()
            }
            psiDocumentManager.commitDocument(sourceDocument)

            val stubDeclarations = rangeToDeclarations(sourcePsiFile, insertedRangeMarker.startOffset, insertedRangeMarker.endOffset)
            assert(stubDeclarations.size == pastedDeclarations.size) //TODO: can they ever differ?

            val mover = object: Mover {
                override fun invoke(declaration: KtNamedDeclaration, targetContainer: KtElement): KtNamedDeclaration {
                    val index = stubDeclarations.indexOf(declaration)
                    assert(index >= 0)
                    declaration.delete()
                    return pastedDeclarations[index]
                }
            }

            val declarationProcessor = MoveKotlinDeclarationsProcessor(
                    MoveDeclarationsDescriptor(
                            elementsToMove = stubDeclarations,
                            moveTarget = KotlinMoveTargetForExistingElement(targetPsiFile),
                            delegate = MoveDeclarationsDelegate.TopLevel,
                            project = project
                    ),
                    mover
            )

            val declarationUsages = declarationProcessor.findUsages().toList()
//                val changeInfo = ContainerChangeInfo(ContainerInfo.Package(sourcePackageName), ContainerInfo.Package(targetPackageName))
//                val internalUsages = file.getInternalReferencesToUpdateOnPackageNameChange(changeInfo)

            project.executeWriteCommand(commandName, commandGroupId) {

                //                    postProcessMoveUsages(internalUsages) //TODO?
                project.runRefactoringAndKeepDelayedRequests { declarationProcessor.execute(declarationUsages) }

                psiDocumentManager.doPostponedOperationsAndUnblockDocument(sourceDocument)
                assert(insertedRangeMarker.isValid)
                sourceDocument.deleteString(insertedRangeMarker.startOffset, insertedRangeMarker.endOffset)
            }
        }

        private fun insertStubDeclarations(): RangeMarker {
            val insertionOffset = sourcePsiFile.declarations.firstOrNull()?.startOffset ?: sourcePsiFile.textLength
            val textToInsert = "\n//start\n" + stubTexts.joinToString(separator = "\n") + "\n//end\n"
            sourceDocument.insertString(insertionOffset, textToInsert)
            return sourceDocument.createRangeMarker(TextRange(insertionOffset, insertionOffset + textToInsert.length))
        }
    }
}

private fun rangeToDeclarations(file: KtFile, startOffset: Int, endOffset: Int): List<KtNamedDeclaration> {
    val elementsInRange = file.elementsInRange(TextRange(startOffset, endOffset))
    val meaningfulElements = elementsInRange.filterNot { it is PsiWhiteSpace || it is PsiComment }
    if (meaningfulElements.isEmpty()) return emptyList()
    if (!meaningfulElements.all { it is KtNamedDeclaration }) return emptyList()
    @Suppress("UNCHECKED_CAST")
    return meaningfulElements as List<KtNamedDeclaration>
}

