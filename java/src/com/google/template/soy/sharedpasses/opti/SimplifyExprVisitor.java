/*
 * Copyright 2011 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.template.soy.sharedpasses.opti;

import com.google.template.soy.data.SoyValue;
import com.google.template.soy.data.internalutils.InternalValueUtils;
import com.google.template.soy.data.restricted.BooleanData;
import com.google.template.soy.data.restricted.FloatData;
import com.google.template.soy.data.restricted.IntegerData;
import com.google.template.soy.data.restricted.NullData;
import com.google.template.soy.data.restricted.PrimitiveData;
import com.google.template.soy.data.restricted.StringData;
import com.google.template.soy.exprtree.AbstractExprNodeVisitor;
import com.google.template.soy.exprtree.BooleanNode;
import com.google.template.soy.exprtree.ExprEquivalence;
import com.google.template.soy.exprtree.ExprNode;
import com.google.template.soy.exprtree.ExprNode.ParentExprNode;
import com.google.template.soy.exprtree.ExprNode.PrimitiveNode;
import com.google.template.soy.exprtree.ExprRootNode;
import com.google.template.soy.exprtree.FieldAccessNode;
import com.google.template.soy.exprtree.FloatNode;
import com.google.template.soy.exprtree.FunctionNode;
import com.google.template.soy.exprtree.GlobalNode;
import com.google.template.soy.exprtree.IntegerNode;
import com.google.template.soy.exprtree.ItemAccessNode;
import com.google.template.soy.exprtree.ListLiteralNode;
import com.google.template.soy.exprtree.MapLiteralNode;
import com.google.template.soy.exprtree.NullNode;
import com.google.template.soy.exprtree.OperatorNodes.AndOpNode;
import com.google.template.soy.exprtree.OperatorNodes.ConditionalOpNode;
import com.google.template.soy.exprtree.OperatorNodes.OrOpNode;
import com.google.template.soy.exprtree.ProtoInitNode;
import com.google.template.soy.exprtree.RecordLiteralNode;
import com.google.template.soy.exprtree.StringNode;
import com.google.template.soy.logging.LoggingFunction;
import com.google.template.soy.shared.internal.BuiltinFunction;
import com.google.template.soy.sharedpasses.render.Environment;
import com.google.template.soy.sharedpasses.render.RenderException;

/**
 * Visitor for simplifying expressions based on constant values known at compile time.
 *
 * <p>Package-private helper for {@link SimplifyVisitor}.
 *
 */
final class SimplifyExprVisitor extends AbstractExprNodeVisitor<Void> {

  /** The PreevalVisitor for this instance (can reuse). */
  private final PreevalVisitor preevalVisitor;

  SimplifyExprVisitor() {
    this.preevalVisitor = new PreevalVisitor(Environment.prerenderingEnvironment());
  }

  // -----------------------------------------------------------------------------------------------
  // Implementation for root node.

  @Override
  protected void visitExprRootNode(ExprRootNode node) {
    visit(node.getRoot());
  }

  // -----------------------------------------------------------------------------------------------
  // Implementations for collection nodes.

  @Override
  protected void visitListLiteralNode(ListLiteralNode node) {
    // Visit children only. We cannot simplify the list literal itself.
    visitChildren(node);
  }

  @Override
  protected void visitRecordLiteralNode(RecordLiteralNode node) {
    // Visit children only. We cannot simplify the record literal itself.
    visitChildren(node);
  }

  @Override
  protected void visitMapLiteralNode(MapLiteralNode node) {
    // Visit children only. We cannot simplify the map literal itself.
    visitChildren(node);
  }

  // -----------------------------------------------------------------------------------------------
  // Implementations for operators.

  @Override
  protected void visitAndOpNode(AndOpNode node) {

    // Recurse.
    visitChildren(node);

    // Can simplify if either child is constant. We assume no side-effects.
    SoyValue operand0 = getConstantOrNull(node.getChild(0));
    if (operand0 != null) {
      ExprNode replacementNode = operand0.coerceToBoolean() ? node.getChild(1) : node.getChild(0);
      node.getParent().replaceChild(node, replacementNode);
    }
  }

  @Override
  protected void visitOrOpNode(OrOpNode node) {

    // Recurse.
    visitChildren(node);

    // Can simplify if either child is constant. We assume no side-effects.
    SoyValue operand0 = getConstantOrNull(node.getChild(0));
    if (operand0 != null) {
      ExprNode replacementNode = operand0.coerceToBoolean() ? node.getChild(0) : node.getChild(1);
      node.getParent().replaceChild(node, replacementNode);
    }
  }

  @Override
  protected void visitConditionalOpNode(ConditionalOpNode node) {

    // Recurse.
    visitChildren(node);

    // Can simplify if operand0 is constant. We assume no side-effects.
    SoyValue operand0 = getConstantOrNull(node.getChild(0));
    if (operand0 == null) {
      return; // cannot simplify
    }

    ExprNode replacementNode = operand0.coerceToBoolean() ? node.getChild(1) : node.getChild(2);
    node.getParent().replaceChild(node, replacementNode);
  }

  // Optimize accessing fields of record and proto literals: ['a': 'b'].a => 'b'
  // This is unlikely to occur by chance, but things like the msgWithId() function may introduce
  // this code since it desugars into a record literal.
  @Override
  protected void visitFieldAccessNode(FieldAccessNode node) {
    // simplify children first
    visitChildren(node);
    ExprNode baseExpr = node.getChild(0);
    if (baseExpr instanceof RecordLiteralNode) {
      RecordLiteralNode recordLiteral = (RecordLiteralNode) baseExpr;
      for (int i = 0; i < recordLiteral.numChildren(); i++) {
        if (recordLiteral.getKey(i).identifier().equals(node.getFieldName())) {
          node.getParent().replaceChild(node, recordLiteral.getChild(i));
          return;
        }
      }
      // replace with null?  this should have been a compiler error.
    } else if (baseExpr instanceof ProtoInitNode) {
      ProtoInitNode protoInit = (ProtoInitNode) baseExpr;
      int fieldIndex = -1;
      for (int i = 0; i < protoInit.getParamNames().size(); i++) {
        if (protoInit.getParamNames().get(i).identifier().equals(node.getFieldName())) {
          fieldIndex = i;
          break;
        }
      }
      if (fieldIndex != -1) {
        node.getParent().replaceChild(node, protoInit.getChild(fieldIndex));
      } else {
        // here we could replace with a default value or null, but for now, do nothing, rather than
        // implement proto default field semantics.
      }
    }
  }

  // Optimize accessing map and list literals.  This covers expressions like [1,2,3][1] and
  // map('a':1)['a']
  // This is fairly unlikely to happen in practice, but is being done for consistency with the other
  // aggregate literals above.  This might happen for things like pure functions that return
  // collections
  @Override
  protected void visitItemAccessNode(ItemAccessNode node) {
    // simplify children first
    visitChildren(node);
    ExprNode baseExpr = node.getChild(0);
    ExprNode keyExpr = node.getChild(1);
    if (baseExpr instanceof ListLiteralNode && keyExpr instanceof IntegerNode) {
      ListLiteralNode listLiteral = (ListLiteralNode) baseExpr;
      long index = ((IntegerNode) keyExpr).getValue();
      if (index > 0 && index < listLiteral.numChildren()) {
        node.getParent().replaceChild(node, listLiteral.getChild((int) index));
      } else {
        // out of range
        node.getParent().replaceChild(node, new NullNode(node.getSourceLocation()));
      }
    } else if (baseExpr instanceof MapLiteralNode) {
      MapLiteralNode mapLiteral = (MapLiteralNode) baseExpr;
      for (int i = 0; i < mapLiteral.numChildren(); i += 2) {
        if (ExprEquivalence.get().equivalent(keyExpr, mapLiteral.getChild(i))) {
          node.getParent().replaceChild(node, mapLiteral.getChild(i + 1));
          return;
        }
      }
      // no matching key
      node.getParent().replaceChild(node, new NullNode(node.getSourceLocation()));
    }
  }

  // -----------------------------------------------------------------------------------------------
  // Implementations for functions.

  @Override
  protected void visitFunctionNode(FunctionNode node) {

    // Cannot simplify nonplugin functions.
    // TODO(brndn): we can actually simplify checkNotNull.
    if (node.getSoyFunction() instanceof BuiltinFunction) {
      return;
    }
    if (node.getSoyFunction() instanceof LoggingFunction) {
      return;
    }
    // Default to fallback implementation.
    visitExprNode(node);
  }

  // -----------------------------------------------------------------------------------------------
  // Fallback implementation.

  @Override
  protected void visitExprNode(ExprNode node) {

    if (!(node instanceof ParentExprNode)) {
      return;
    }
    ParentExprNode nodeAsParent = (ParentExprNode) node;

    // Recurse.
    visitChildren(nodeAsParent);

    // If all children are constants, we attempt to preevaluate this node and replace it with a
    // constant.
    for (ExprNode child : nodeAsParent.getChildren()) {
      if (!isConstant(child)) {
        return; // cannot preevaluate
      }
    }
    attemptPreeval(nodeAsParent);
  }

  // -----------------------------------------------------------------------------------------------
  // Helpers.

  /**
   * Attempts to preevaluate a node. If successful, the node is replaced with a new constant node in
   * the tree. If unsuccessful, the tree is not changed.
   */
  private void attemptPreeval(ExprNode node) {

    // Note that we need to catch RenderException because preevaluation may fail, e.g. when
    // (a) the expression uses a bidi function that needs bidiGlobalDir to be in scope, but the
    //     apiCallScope is not currently active,
    // (b) the expression uses an external function (Soy V1 syntax),
    // (c) other cases I haven't thought up.

    SoyValue preevalResult;
    try {
      preevalResult = preevalVisitor.exec(node);
    } catch (RenderException e) {
      return; // failed to preevaluate
    }
    PrimitiveNode newNode =
        InternalValueUtils.convertPrimitiveDataToExpr(
            (PrimitiveData) preevalResult, node.getSourceLocation());
    if (newNode != null) {
      node.getParent().replaceChild(node, newNode);
    }
  }

  static boolean isConstant(ExprNode expr) {
    return (expr instanceof GlobalNode && ((GlobalNode) expr).isResolved())
        || expr instanceof PrimitiveNode;
  }

  /** Returns the value of the given expression if it's constant, else returns null. */
  static SoyValue getConstantOrNull(ExprNode expr) {

    switch (expr.getKind()) {
      case NULL_NODE:
        return NullData.INSTANCE;
      case BOOLEAN_NODE:
        return BooleanData.forValue(((BooleanNode) expr).getValue());
      case INTEGER_NODE:
        return IntegerData.forValue(((IntegerNode) expr).getValue());
      case FLOAT_NODE:
        return FloatData.forValue(((FloatNode) expr).getValue());
      case STRING_NODE:
        return StringData.forValue(((StringNode) expr).getValue());
      case GLOBAL_NODE:
        GlobalNode global = (GlobalNode) expr;
        if (global.isResolved()) {
          return getConstantOrNull(global.getValue());
        }
        return null;
      default:
        return null;
    }
  }
}
