/*
 * Copyright 2008 Google Inc.
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

package com.google.template.soy.msgs.internal;

import static java.util.Comparator.comparing;

import com.google.common.base.Preconditions;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.template.soy.msgs.SoyMsgBundle;
import com.google.template.soy.msgs.internal.MsgUtils.MsgPartsAndIds;
import com.google.template.soy.msgs.restricted.SoyMsg;
import com.google.template.soy.msgs.restricted.SoyMsgBundleImpl;
import com.google.template.soy.soytree.AbstractSoyNodeVisitor;
import com.google.template.soy.soytree.MsgNode;
import com.google.template.soy.soytree.SoyFileNode;
import com.google.template.soy.soytree.SoyFileSetNode;
import com.google.template.soy.soytree.SoyNode;
import com.google.template.soy.soytree.SoyNode.ParentSoyNode;
import com.google.template.soy.soytree.TemplateDelegateNode;
import com.google.template.soy.soytree.TemplateNode;
import java.util.Collections;
import java.util.List;

/**
 * Visitor for extracting messages from a Soy parse tree.
 *
 * <p>Important: Do not use outside of Soy code (treat as superpackage-private).
 *
 * <p>{@link #exec} should be called on a full parse tree. All messages will be extracted and
 * returned in a {@code SoyMsgBundle} (locale "en").
 *
 */
public final class ExtractMsgsVisitor extends AbstractSoyNodeVisitor<SoyMsgBundle> {

  /** List of messages collected during the pass. */
  private List<SoyMsg> msgs;

  private String currentTemplate;

  /**
   * Returns a SoyMsgBundle containing all messages extracted from the given SoyFileSetNode or
   * SoyFileNode (locale string is null).
   */
  @Override
  public SoyMsgBundle exec(SoyNode node) {
    Preconditions.checkArgument(node instanceof SoyFileSetNode || node instanceof SoyFileNode);

    msgs = Lists.newArrayList();
    visit(node);
    // the messages in this list only have one source location.
    // messages gain extra source locations when merged together in a bundle.
    Collections.sort(
        msgs, comparing(m -> Iterables.getOnlyElement(m.getSourceLocations()).sourceLocation()));
    return new SoyMsgBundleImpl(null, msgs);
  }

  // -----------------------------------------------------------------------------------------------
  // Implementations for specific nodes.

  @Override
  protected void visitMsgNode(MsgNode node) {
    MsgPartsAndIds msgPartsAndIds = MsgUtils.buildMsgPartsAndComputeMsgIdForDualFormat(node);
    SoyMsg.Builder builder = SoyMsg.builder().setId(msgPartsAndIds.id);
    if (node.getMeaning() != null) {
      builder.setMeaning(node.getMeaning());
    }
    SoyMsg msg =
        builder
            .setDesc(node.getDesc())
            .setIsHidden(node.isHidden())
            .setContentType(node.getContentType())
            .addSourceLocation(node.getSourceLocation(), currentTemplate)
            .setIsPlrselMsg(node.isPlrselMsg())
            .setParts(msgPartsAndIds.parts)
            .setHasFallback(
                // we have a fallback if our parent has 2 children (the msg and the fallbackmsg) and
                // we are the msg
                node.getParent().numChildren() == 2 && node.getParent().getChildIndex(node) == 0)
            .build();
    msgs.add(msg);
  }

  @Override
  protected void visitTemplateNode(TemplateNode node) {
    if (node instanceof TemplateDelegateNode) {
      currentTemplate = ((TemplateDelegateNode) node).getDelTemplateName();
    }
    currentTemplate = node.getTemplateName();
    super.visitTemplateNode(node);
  }

  // -----------------------------------------------------------------------------------------------
  // Fallback implementation.

  @Override
  protected void visitSoyNode(SoyNode node) {
    if (node instanceof ParentSoyNode<?>) {
      visitChildren((ParentSoyNode<?>) node);
    }
  }
}
