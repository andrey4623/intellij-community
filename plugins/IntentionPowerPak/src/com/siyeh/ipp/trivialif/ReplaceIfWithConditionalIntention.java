/*
 * Copyright 2003-2009 Dave Griffith, Bas Leijdekkers
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
package com.siyeh.ipp.trivialif;

import com.intellij.psi.*;
import com.intellij.psi.impl.PsiDiamondTypeUtil;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.IncorrectOperationException;
import com.siyeh.ipp.base.Intention;
import com.siyeh.ipp.base.PsiElementPredicate;
import com.siyeh.ipp.psiutils.ConditionalUtils;
import com.siyeh.ipp.psiutils.ParenthesesUtils;
import org.jetbrains.annotations.NotNull;

public class ReplaceIfWithConditionalIntention extends Intention {

    @Override
    @NotNull
    public PsiElementPredicate getElementPredicate() {
        return new ReplaceIfWithConditionalPredicate();
    }

    @Override
    public void processIntention(@NotNull PsiElement element)
            throws IncorrectOperationException {
        final PsiIfStatement ifStatement = (PsiIfStatement)element.getParent();
        if (ifStatement == null) {
            return;
        }
        if (ReplaceIfWithConditionalPredicate.isReplaceableAssignment(
                ifStatement)) {
            final PsiExpression condition = ifStatement.getCondition();
            if (condition == null) {
                return;
            }
            final PsiStatement thenBranch = ifStatement.getThenBranch();
            final PsiExpressionStatement strippedThenBranch =
                    (PsiExpressionStatement)ConditionalUtils.stripBraces(
                            thenBranch);
            final PsiStatement elseBranch = ifStatement.getElseBranch();
            final PsiExpressionStatement strippedElseBranch =
                    (PsiExpressionStatement)ConditionalUtils.stripBraces(
                            elseBranch);
            final PsiAssignmentExpression thenAssign =
                    (PsiAssignmentExpression)strippedThenBranch.getExpression();
            final PsiAssignmentExpression elseAssign =
                    (PsiAssignmentExpression)strippedElseBranch.getExpression();
            final PsiExpression lhs = thenAssign.getLExpression();
            final String lhsText = lhs.getText();
            final PsiJavaToken sign = thenAssign.getOperationSign();
            final String operator = sign.getText();
            final PsiExpression thenRhs = thenAssign.getRExpression();
            if (thenRhs == null) {
                return;
            }
            final PsiExpression elseRhs = elseAssign.getRExpression();
            if (elseRhs == null) {
                return;
            }
            final String conditional = getConditionalText(condition, thenRhs,
                    elseRhs, thenAssign.getType());
            replaceStatement(lhsText + operator + conditional + ';',
                    ifStatement);
        } else if (ReplaceIfWithConditionalPredicate.isReplaceableReturn(
                ifStatement)) {
            final PsiExpression condition = ifStatement.getCondition();
            if (condition == null) {
                return;
            }
            final PsiStatement thenBranch = ifStatement.getThenBranch();
            final PsiReturnStatement thenReturn =
                    (PsiReturnStatement)ConditionalUtils.stripBraces(thenBranch);
            final PsiStatement elseBranch = ifStatement.getElseBranch();
            final PsiReturnStatement elseReturn =
                    (PsiReturnStatement)ConditionalUtils.stripBraces(elseBranch);
            final PsiExpression thenReturnValue = thenReturn.getReturnValue();
            if (thenReturnValue == null) {
                return;
            }
            final PsiExpression elseReturnValue = elseReturn.getReturnValue();
            if (elseReturnValue == null) {
                return;
            }
            final PsiMethod method =
                    PsiTreeUtil.getParentOfType(thenReturn, PsiMethod.class);
            if (method == null) {
                return;
            }
            final PsiType returnType = method.getReturnType();
            final String conditional = getConditionalText(condition,
                    thenReturnValue, elseReturnValue, returnType);
            replaceStatement("return " + conditional + ';', ifStatement);
        } else if (ReplaceIfWithConditionalPredicate.isReplaceableImplicitReturn(
                ifStatement)) {
            final PsiExpression condition = ifStatement.getCondition();
            if (condition == null) {
                return;
            }
            final PsiStatement rawThenBranch = ifStatement.getThenBranch();
            final PsiReturnStatement thenBranch =
                    (PsiReturnStatement)ConditionalUtils.stripBraces(
                            rawThenBranch);
            final PsiExpression thenReturnValue = thenBranch.getReturnValue();
            if (thenReturnValue == null) {
                return;
            }
            final PsiReturnStatement elseBranch =
                    PsiTreeUtil.getNextSiblingOfType(ifStatement,
                            PsiReturnStatement.class);
            if (elseBranch == null) {
                return;
            }
            final PsiExpression elseReturnValue = elseBranch.getReturnValue();
            if (elseReturnValue == null) {
                return;
            }
            final PsiMethod method =
                    PsiTreeUtil.getParentOfType(thenBranch, PsiMethod.class);
            if (method == null) {
                return;
            }
            final PsiType methodType = method.getReturnType();
            final String conditional =
                    getConditionalText(condition, thenReturnValue,
                            elseReturnValue, methodType);
            if (conditional == null) {
                return;
            }
            replaceStatement("return " + conditional + ';', ifStatement);
            elseBranch.delete();
        }
    }

    private static String getConditionalText(PsiExpression condition,
                                             PsiExpression thenValue,
                                             PsiExpression elseValue,
                                             PsiType requiredType) {
        condition = ParenthesesUtils.stripParentheses(condition);
        thenValue = PsiDiamondTypeUtil.expandTopLevelDiamondsInside(ParenthesesUtils.stripParentheses(thenValue));
        if (thenValue == null) {
            return null;
        }
        elseValue = PsiDiamondTypeUtil.expandTopLevelDiamondsInside(ParenthesesUtils.stripParentheses(elseValue));
        if (elseValue ==  null) {
            return null;
        }
        final StringBuilder conditional = new StringBuilder();
        final String conditionText = getExpressionText(condition);
        conditional.append(conditionText);
        conditional.append('?');
        final PsiType thenType = thenValue.getType();
        final PsiType elseType = elseValue.getType();
        if (thenType instanceof PsiPrimitiveType &&
                !PsiType.NULL.equals(thenType) &&
                !(elseType instanceof PsiPrimitiveType) &&
                !(requiredType instanceof PsiPrimitiveType)) {
            // prevent unboxing of boxed value to preserve semantics (IDEADEV-36008)
            final PsiPrimitiveType primitiveType = (PsiPrimitiveType) thenType;
            conditional.append(primitiveType.getBoxedTypeName());
            conditional.append(".valueOf(");
            conditional.append(thenValue.getText());
            conditional.append("):");
            conditional.append(getExpressionText(elseValue));
        } else if (elseType instanceof PsiPrimitiveType &&
                !PsiType.NULL.equals(elseType) &&
                !(thenType instanceof PsiPrimitiveType) &&
                !(requiredType instanceof PsiPrimitiveType)) {
            // prevent unboxing of boxed value to preserve semantics (IDEADEV-36008)
            conditional.append(getExpressionText(thenValue));
            conditional.append(':');
            final PsiPrimitiveType primitiveType = (PsiPrimitiveType) elseType;
            conditional.append(primitiveType.getBoxedTypeName());
            conditional.append(".valueOf(");
            conditional.append(elseValue.getText());
            conditional.append(')');
        } else {
            conditional.append(getExpressionText(thenValue));
            conditional.append(':');
            conditional.append(getExpressionText(elseValue));
        }
        return conditional.toString();
    }

    private static String getExpressionText(PsiExpression expression) {
        if (ParenthesesUtils.getPrecedence(expression) <=
                ParenthesesUtils.CONDITIONAL_PRECEDENCE) {
            return expression.getText();
        } else {
            return '(' + expression.getText() + ')';
        }
    }
}
