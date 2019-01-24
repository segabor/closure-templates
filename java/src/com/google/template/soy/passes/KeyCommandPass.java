/*
 * Copyright 2018 Google Inc.
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

package com.google.template.soy.passes;

import com.google.template.soy.base.internal.IdGenerator;
import com.google.template.soy.error.ErrorReporter;
import com.google.template.soy.error.SoyErrorKind;
import com.google.template.soy.exprtree.ExprNode;
import com.google.template.soy.soytree.HtmlAttributeNode;
import com.google.template.soy.soytree.HtmlOpenTagNode;
import com.google.template.soy.soytree.HtmlTagNode;
import com.google.template.soy.soytree.KeyNode;
import com.google.template.soy.soytree.SoyFileNode;
import com.google.template.soy.soytree.SoyNode;
import com.google.template.soy.soytree.SoyTreeUtils;
import com.google.template.soy.types.IntType;
import com.google.template.soy.types.SoyType.Kind;
import com.google.template.soy.types.StringType;
import java.util.Objects;

/**
 * Validates restrictions on DOM nodes with key commands (e.g. {@code <div {key 'foo'}></div>}).
 * Note that while these restrictions are applied due to how Soy handles incremental dom keys, this
 * pass runs in every backend for consistency.
 */
final class KeyCommandPass extends CompilerFilePass {

  private static final SoyErrorKind KEY_ATTR_DIRECT_CHILD_OF_OPEN_TAG =
      SoyErrorKind.of(
          "The `key` command must be directly nested within an HTML open tag "
              + "(e.g. `<div '{'key 'foo''}'></div>`).");

  private static final SoyErrorKind DUPLICATE_KEY_ATTR =
      SoyErrorKind.of("The key attribute is deprecated. Instead, use the '{'key'}' command.");

  private static final SoyErrorKind UNSUPPORTED_TYPE =
      SoyErrorKind.of("Unsupported type ''{0}'': keys must be of type string or integer.");

  private static final SoyErrorKind KEY_ELEMENT_AMBIGUOUS =
      SoyErrorKind.of(
          "Key elements must have open tags that map to a single HTML close tag and vice versa.");

  private final boolean disableAllTypeChecking;
  private final ErrorReporter errorReporter;

  KeyCommandPass(ErrorReporter errorReporter, boolean disableAllTypeChecking) {
    this.disableAllTypeChecking = disableAllTypeChecking;
    this.errorReporter = errorReporter;
  }

  @Override
  public void run(SoyFileNode file, IdGenerator nodeIdGen) {
    for (KeyNode node : SoyTreeUtils.getAllNodesOfType(file, KeyNode.class)) {
      checkNodeIsValidChildOfOpenTagNode(node);
      checkNoDuplicateKeyAttribute(node);
      if (!disableAllTypeChecking) {
        checkNodeIsSupportedType(node);
      }
    }
  }

  private void checkNodeIsValidChildOfOpenTagNode(KeyNode node) {
    if (!(node.getParent() instanceof HtmlOpenTagNode)) {
      errorReporter.report(node.getSourceLocation(), KEY_ATTR_DIRECT_CHILD_OF_OPEN_TAG);
      return;
    }
    HtmlOpenTagNode tagNode = (HtmlOpenTagNode) node.getParent();
    if (tagNode.getTaggedPairs().size() > 1) {
      errorReporter.report(node.getSourceLocation(), KEY_ELEMENT_AMBIGUOUS);
      return;
    }
    // TODO: Remove when pass is turned on.
    if (tagNode.getTaggedPairs().size() != 0) {
      checkOneOpenTagNodeMappedToOneCloseTag(tagNode);
    }
  }

  private void checkOneOpenTagNodeMappedToOneCloseTag(HtmlOpenTagNode tagNode) {
    if (tagNode.getTaggedPairs().size() != 1) {
      errorReporter.report(tagNode.getSourceLocation(), KEY_ELEMENT_AMBIGUOUS);
      return;
    }
    HtmlTagNode closeTag = tagNode.getTaggedPairs().get(0);
    if (closeTag.getTaggedPairs().size() != 1
        || !Objects.equals(closeTag.getTaggedPairs().get(0), tagNode)) {
      errorReporter.report(tagNode.getSourceLocation(), KEY_ELEMENT_AMBIGUOUS);
    }
  }

  //  TODO(b/119309461): Remove after migration is complete.
  private void checkNoDuplicateKeyAttribute(KeyNode node) {
    SoyNode parentNode = node.getParent();
    if (!(parentNode instanceof HtmlOpenTagNode)) {
      // Error thrown before this.
      return;
    }

    HtmlAttributeNode keyAttrNode = ((HtmlOpenTagNode) parentNode).getDirectAttributeNamed("key");
    if (keyAttrNode != null) {
      errorReporter.report(keyAttrNode.getSourceLocation(), DUPLICATE_KEY_ATTR);
    }
  }

  private void checkNodeIsSupportedType(KeyNode node) {
    ExprNode expr = node.getExpr().getRoot();
    if (expr.getType().getKind() == Kind.ANY || expr.getType().getKind() == Kind.UNKNOWN) {
      errorReporter.report(node.getSourceLocation(), UNSUPPORTED_TYPE, expr.getType());
      return;
    }

    // Note that GenIncrementalDomCodeVisitor also asserts that a supported type is passed
    // in, so for union types (e.g. string|bool), that check will catch a bool passed in
    // at runtime.
    if (!expr.getType().isAssignableFrom(IntType.getInstance())
        && !expr.getType().isAssignableFrom(StringType.getInstance())) {
      errorReporter.report(node.getSourceLocation(), UNSUPPORTED_TYPE, expr.getType());
    }
  }
}
