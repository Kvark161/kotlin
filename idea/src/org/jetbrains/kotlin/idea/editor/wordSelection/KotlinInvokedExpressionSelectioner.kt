/*
 * Copyright 2010-2016 JetBrains s.r.o.
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

package org.jetbrains.kotlin.idea.editor.wordSelection

import com.intellij.codeInsight.editorActions.ExtendWordSelectionHandlerBase
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.psiUtil.endOffset
import org.jetbrains.kotlin.psi.psiUtil.startOffset

class KotlinInvokedExpressionSelectioner : ExtendWordSelectionHandlerBase() {
    override fun canSelect(e: PsiElement?): Boolean {
        return e is KtDotQualifiedExpression
    }

    override fun select(e: PsiElement?, editorText: CharSequence?, cursorOffset: Int, editor: Editor): List<TextRange>? {
        if (e !is KtDotQualifiedExpression) return null
        val endOffset = e.selectorExpression?.children?.firstOrNull { it is KtNameReferenceExpression }?.endOffset ?: return null
        return listOf(TextRange(e.startOffset, endOffset))
    }
}