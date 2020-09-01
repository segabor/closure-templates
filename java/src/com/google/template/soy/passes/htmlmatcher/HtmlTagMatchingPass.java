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

package com.google.template.soy.passes.htmlmatcher;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.LinkedHashMultimap;
import com.google.common.collect.SetMultimap;
import com.google.template.soy.base.internal.IdGenerator;
import com.google.template.soy.basetree.CopyState;
import com.google.template.soy.error.ErrorReporter;
import com.google.template.soy.error.SoyErrorKind;
import com.google.template.soy.exprtree.ExprEquivalence;
import com.google.template.soy.passes.htmlmatcher.HtmlMatcherGraphNode.EdgeKind;
import com.google.template.soy.soytree.HtmlCloseTagNode;
import com.google.template.soy.soytree.HtmlOpenTagNode;
import com.google.template.soy.soytree.HtmlTagNode;
import com.google.template.soy.soytree.HtmlTagNode.TagExistence;
import com.google.template.soy.soytree.SoyNode.ParentSoyNode;
import com.google.template.soy.soytree.SoyNode.StandaloneNode;
import com.google.template.soy.soytree.TagName;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import javax.annotation.Nullable;

/**
 * Pass for checking the balance of open tag nodes with possible close tags. Because Soy contains
 * control flow, it is possible for a open tag node to possible map to different close tags. For
 * example, consider the following:
 *
 * <pre>
 * {@code} {
 *   <div>
 *   {if $foo}</div><div>{/if}
 *   </div>
 * }
 * </pre>
 *
 * Because of this, we need to consider all possible paths statically inferable from the template at
 * hand (calls are not considered). In this example, we need to check if $foo==true and $foo ==
 * false generate correct DOM. This visitor verifies that all possible paths are legitimate and then
 * annotates open tags and close tags with their possible pairs.
 */
public final class HtmlTagMatchingPass {
  private static final SoyErrorKind INVALID_CLOSE_TAG =
      SoyErrorKind.of("''{0}'' tag is a void element and must not specify a close tag.");
  private static final SoyErrorKind INVALID_SELF_CLOSING_TAG =
      SoyErrorKind.of("''{0}'' tag is not allowed to be self-closing.");
  private static final String UNEXPECTED_CLOSE_TAG = "Unexpected HTML close tag.";

  private static final String UNEXPECTED_CLOSE_TAG_KNOWN =
      "Unexpected HTML close tag. Expected to match the ''<{0}>'' at {1}.";

  private static final String BLOCK_QUALIFIER = " Tags within a %s must be internally balanced.";

  private static final String UNEXPECTED_OPEN_TAG_ALWAYS =
      "This HTML open tag is never matched with a close tag.";
  private static final String UNEXPECTED_OPEN_TAG_SOMETIMES =
      "This HTML open tag does not consistently match with a close tag.";
  private static final String EXPECTED_TAG_NAME = "Expected an html tag name.";

  private static final Optional<HtmlTagNode> INVALID_NODE = Optional.empty();

  private final ErrorReporter errorReporter;
  /** Required in order to generate synthetic nodes. */
  private final IdGenerator idGenerator;
  /**
   * This pass runs itself recursively inside if condition. When doing so, it passes itself this
   * variable so that when there are errors, no annotations occur
   */
  private final boolean inCondition;

  /**
   * Keeps track of the current depth within HTML tags containing foreign content. This pass runs
   * itself recursively on block nodes. If inside foreign content, various rules apply.
   */
  private final int foreignContentTagDepth;

  /** Used for error messages to detail what context an error is in. */
  @Nullable private final String parentBlockType;

  /**
   * Record of nodes and their related tag nodes. This is used to "save" a record of actions to be
   * taken. At the end of the graph traversal, if there are no errors, "commit" the changes.
   */
  private final SetMultimap<HtmlTagNode, Optional<HtmlTagNode>> annotationMap =
      LinkedHashMultimap.create();

  private final ExprEquivalence exprEquivalence = new ExprEquivalence();

  public HtmlTagMatchingPass(
      ErrorReporter errorReporter,
      IdGenerator idGenerator,
      boolean inCondition,
      int foreignContentTagDepth,
      String parentBlockType) {
    this.foreignContentTagDepth = foreignContentTagDepth;
    this.parentBlockType = parentBlockType;
    this.errorReporter = errorReporter;
    this.idGenerator = idGenerator;
    this.inCondition = inCondition;
  }

  private SoyErrorKind makeSoyErrorKind(String soyError) {
    return SoyErrorKind.of(
        soyError
            + (parentBlockType != null ? String.format(BLOCK_QUALIFIER, parentBlockType) : ""));
  }

  /**
   * Represents the state of the HTML graph traversal. Each tag has context on whether it is in
   * foreign content and a reference to the previous node. This allows pushing/popping to create a
   * traversal of the HTML Matcher Graph.
   */
  class HtmlStack {
    final HtmlOpenTagNode tagNode;
    final int foreignContentTagDepth;
    final HtmlStack prev;

    HtmlStack(HtmlOpenTagNode tagNode, int foreignContentTagDepth, HtmlStack prev) {
      this.tagNode = tagNode;
      this.foreignContentTagDepth = foreignContentTagDepth;
      this.prev = prev;
    }

    HtmlStack push(HtmlOpenTagNode tagNode, int foreignContentTagDepth) {
      return new HtmlStack(tagNode, foreignContentTagDepth, this);
    }

    HtmlStack pop() {
      return prev;
    }

    boolean isEmpty() {
      return tagNode == null;
    }

    @Override
    public String toString() {
      if (prev == null) {
        return "[START]";
      }
      return prev + "->" + tagNode.getTagName();
    }
  }

  /**
   * Runs the HtmlTagMatchingPass.
   *
   * <p>The pass does the following:
   *
   * <ol>
   *   <li>Traverse the HTML matcher graph and create a set of open -> close tag matches
   *   <li>Rebalance the code paths, injecting synthetic close tags to balance optional open tags.
   *       Optional open tags are defined here: <a
   *       href="https://www.w3.org/TR/html5/syntax.html#optional-tags">https://www.w3.org/TR/html5/syntax.html#optional-tags</a>.
   *       <p>&ndash; <em>Note:</em> Block nodes (such as {@code {msg} or {let}}) are discretely
   *       rebalanced, annotated and error-checked. By definition, a block node must internally
   *       balance HTML tags.
   *   <li>Check for tag mismatch errors in the fully balanced code paths.
   *       <p>&ndash; Afterwards, annotate the open with the list of possible close tags, and the
   *       close tags with the list of possible open tags.
   * </ol>
   */
  public void run(HtmlMatcherGraph htmlMatcherGraph) {
    if (!htmlMatcherGraph.getRootNode().isPresent()) {
      // Empty graph.
      return;
    }
    visit(htmlMatcherGraph.getRootNode().get());
    for (HtmlTagNode tag : annotationMap.keySet()) {
      if (tag instanceof HtmlOpenTagNode) {
        HtmlOpenTagNode openTag = (HtmlOpenTagNode) tag;
        if (!annotationMap.containsEntry(openTag, INVALID_NODE)) {
          continue;
        }
        if (annotationMap.get(openTag).size() == 1) {
          if (!tag.getTagName().isExcludedOptionalTag()) {
            errorReporter.report(
                openTag.getSourceLocation(), makeSoyErrorKind(UNEXPECTED_OPEN_TAG_ALWAYS));
          }
        } else {
          errorReporter.report(
              openTag.getSourceLocation(), makeSoyErrorKind(UNEXPECTED_OPEN_TAG_SOMETIMES));
        }
      }
    }
    // Do not annotate in inCondition because if there are errors, the nodes will be annotated
    // in the parent pass. The reason this happens is when the condition node is not balanced
    // internally but balanced globally.
    if (errorReporter.hasErrors() && inCondition) {
      return;
    }
    for (HtmlTagNode openTag : annotationMap.keySet()) {
      for (Optional<HtmlTagNode> closeTag : annotationMap.get(openTag)) {
        if (closeTag.isPresent()) {
          openTag.addTagPair(closeTag.get());
          closeTag.get().addTagPair(openTag);
        }
      }
    }
  }

  /**
   * Rebalances HTML tags when necessary.
   *
   * <p>If an optional tag is encountered, inject a synthetic close tag right before the tag that
   * performs the implicit close. For example, this HTML:
   *
   * <pre>{@code
   * <ul>
   *   <li>List 1
   *   <li>List 2
   * </ul>
   * }</pre>
   *
   * <p>Will be rewritten to look like this logical HTML (note the addition of the {@code </li>}
   * tags):
   *
   * <pre>{@code
   * <ul>
   *   <li>List 1</li>
   *   <li>List 2</li>
   * </ul>
   * }</pre>
   */
  private void injectCloseTag(
      HtmlOpenTagNode optionalOpenTag, HtmlTagNode destinationTag, IdGenerator idGenerator) {
    StandaloneNode openTagCopy = optionalOpenTag.getTagName().getNode().copy(new CopyState());
    HtmlCloseTagNode syntheticClose =
        new HtmlCloseTagNode(
            idGenerator.genId(),
            openTagCopy,
            optionalOpenTag.getSourceLocation(),
            TagExistence.SYNTHETIC);
    // If destination is null, then insert at the end of the template.
    if (destinationTag == null) {
      int i = optionalOpenTag.getParent().numChildren();
      optionalOpenTag.getParent().addChild(i, syntheticClose);
    } else {
      // This inserts the synthetic close tag right before the open tag.
      ParentSoyNode<StandaloneNode> openTagParent = destinationTag.getParent();
      int i = openTagParent.getChildIndex(destinationTag);
      openTagParent.addChild(i, syntheticClose);
    }

    annotationMap.put(optionalOpenTag, Optional.of(syntheticClose));
    annotationMap.put(syntheticClose, Optional.of(optionalOpenTag));
  }

  @FunctionalInterface
  interface QueuedTask {
    List<QueuedTask> run();
  }

  /** Perform tag matching/error reporting for invalid HTML. */
  private List<QueuedTask> visit(
      HtmlMatcherTagNode tagNode,
      Map<ExprEquivalence.Wrapper, Boolean> exprValueMap,
      HtmlStack stack) {
    HtmlTagNode tag = (HtmlTagNode) tagNode.getSoyNode().get();
    TagName openTagName = tag.getTagName();
    HtmlStack prev = stack;
    switch (tagNode.getTagKind()) {
      case VOID_TAG:
        HtmlOpenTagNode voidTag = (HtmlOpenTagNode) tag;
        // Report errors for non-void tags that are self-closing.
        // For void tags, we don't care if they are self-closing or not. But when we visit
        // a HtmlCloseTagNode we will throw an error if it is a void tag.
        // Ignore this check if we are currently in a foreign content (svg).
        if (stack.foreignContentTagDepth == 0
            && !openTagName.isDefinitelyVoid()
            && voidTag.isSelfClosing()
            && openTagName.isStatic()) {
          errorReporter.report(
              voidTag.getSourceLocation(),
              INVALID_SELF_CLOSING_TAG,
              openTagName.getStaticTagName());
        }
        break;
      case OPEN_TAG:
        HtmlOpenTagNode openTag = (HtmlOpenTagNode) tag;
        // In a case where an open tag can close another open tag (ie <p><p> or <li><li>),
        // check if this is possible by peeking the stack and inject a tag before the open tag.
        if (!prev.isEmpty()) {
          HtmlOpenTagNode optionalTag = stack.tagNode;
          if (optionalTag.getTagName().isDefinitelyOptional()) {
            if (TagName.checkOpenTagClosesOptional(
                openTag.getTagName(), optionalTag.getTagName())) {
              injectCloseTag(optionalTag, openTag, idGenerator);
              prev = prev.pop();
            }
          }
        }
        prev =
            prev.push(
                openTag,
                stack.foreignContentTagDepth + (openTag.getTagName().isForeignContent() ? 1 : 0));
        break;
      case CLOSE_TAG:
        HtmlCloseTagNode closeTag = (HtmlCloseTagNode) tag;
        // Report an error if this node is a void tag. Void tag should never be closed.
        if (closeTag.getTagName().isDefinitelyVoid()) {
          errorReporter.report(
              closeTag.getTagName().getTagLocation(),
              INVALID_CLOSE_TAG,
              closeTag.getTagName().getStaticTagName());
          break;
        }
        // This is for cases similar to {block}</p>{/block}
        if (stack.isEmpty() && !closeTag.getTagName().isExcludedOptionalTag()) {
          errorReporter.report(
              closeTag.getSourceLocation(), makeSoyErrorKind(UNEXPECTED_CLOSE_TAG));
          break;
        }
        prev = stack;
        while (!prev.isEmpty()) {
          HtmlOpenTagNode nextOpenTag = prev.tagNode;
          if (nextOpenTag.getTagName().isStatic() && closeTag.getTagName().isWildCard()) {
            errorReporter.report(
                closeTag.getTagName().getTagLocation(), makeSoyErrorKind(EXPECTED_TAG_NAME));
          }
          if (nextOpenTag.getTagName().equals(closeTag.getTagName())
              || (!nextOpenTag.getTagName().isStatic() && closeTag.getTagName().isWildCard())) {
            annotationMap.put(nextOpenTag, Optional.of(closeTag));
            annotationMap.put(closeTag, Optional.of(nextOpenTag));
            prev = prev.pop();
            break;
          } else if (nextOpenTag.getTagName().isDefinitelyOptional()
              && TagName.checkCloseTagClosesOptional(
                  closeTag.getTagName(), nextOpenTag.getTagName())) {
            // Close tag closes an optional open tag (e.g. <li> ... </ul>). Inject a synthetic
            // close tag that matches `openTag`.
            injectCloseTag(nextOpenTag, closeTag, idGenerator);
            prev = prev.pop();
          } else {
            annotationMap.put(nextOpenTag, INVALID_NODE);
            if (!closeTag.getTagName().isExcludedOptionalTag()) {
              errorReporter.report(
                  closeTag.getSourceLocation(),
                  makeSoyErrorKind(UNEXPECTED_CLOSE_TAG_KNOWN),
                  nextOpenTag.getTagName(),
                  nextOpenTag.getSourceLocation());
            }
            prev = prev.pop();
          }
        }
        break;
    }
    Optional<HtmlMatcherGraphNode> nextNode = tagNode.getNodeForEdgeKind(EdgeKind.TRUE_EDGE);
    return ImmutableList.of(visit(nextNode, exprValueMap, prev));
  }

  /**
   * Blocks must be internally balanced, but require knowing if they are in foreign content or not.
   * Recursively run the tag matcher and throw away the result.
   */
  private List<QueuedTask> visit(
      HtmlMatcherBlockNode blockNode,
      Map<ExprEquivalence.Wrapper, Boolean> exprValueMap,
      HtmlStack stack) {
    if (blockNode.getGraph().getRootNode().isPresent()) {
      new HtmlTagMatchingPass(
              errorReporter,
              idGenerator,
              false,
              stack.foreignContentTagDepth,
              blockNode.getParentBlockType())
          .run(blockNode.getGraph());
    }
    Optional<HtmlMatcherGraphNode> nextNode = blockNode.getNodeForEdgeKind(EdgeKind.TRUE_EDGE);
    return ImmutableList.of(visit(nextNode, exprValueMap, stack));
  }

  /**
   * For a conditional node, we have up to two different paths. In this case, traverse both.
   * However, if we have already visited a branch and concluded that it is internally balanced (in
   * foreign content or not), then don't revisit the branch.
   */
  private List<QueuedTask> visit(
      HtmlMatcherConditionNode condNode,
      Map<ExprEquivalence.Wrapper, Boolean> exprValueMap,
      HtmlStack stack) {
    ExprEquivalence.Wrapper condition = exprEquivalence.wrap(condNode.getExpression());
    // In some cases we may encounter a condition we have already made a decision for. Consider
    // this case:
    // <pre>@code {
    //    {if $foo}<div>{/if}
    //    {if $foo}</div>{/if}
    // }</pre>
    // In this case, it is unnecessary once we have decided that $foo is TRUE to traverse the
    // branch where $foo is FALSE. We save the original state of the value and use it below
    // to decide if we should take a branch.
    Boolean originalState = exprValueMap.getOrDefault(condition, null);

    Optional<HtmlMatcherGraphNode> nextNode = condNode.getNodeForEdgeKind(EdgeKind.TRUE_EDGE);
    Optional<HtmlMatcherGraphNode> nextAltNode = condNode.getNodeForEdgeKind(EdgeKind.FALSE_EDGE);
    ImmutableList.Builder<QueuedTask> tasks = ImmutableList.builder();
    if (!condNode.isInternallyBalanced(stack.foreignContentTagDepth, idGenerator)
        && nextNode.isPresent()
        && !Boolean.FALSE.equals(originalState)) {
      Map<ExprEquivalence.Wrapper, Boolean> lMap = new HashMap<>(exprValueMap);
      lMap.put(condition, true);
      tasks.add(visit(nextNode, lMap, stack));
    }

    if (nextAltNode.isPresent() && !Boolean.TRUE.equals(originalState)) {
      Map<ExprEquivalence.Wrapper, Boolean> rMap = new HashMap<>(exprValueMap);
      rMap.put(condition, false);
      tasks.add(visit(nextAltNode, rMap, stack));
    }
    return tasks.build();
  }

  /** Accumulator nodes mostly work like HTMLMatcherTagNodes, but don't add any elements. */
  private List<QueuedTask> visit(
      HtmlMatcherAccumulatorNode accNode,
      Map<ExprEquivalence.Wrapper, Boolean> exprValueMap,
      HtmlStack stack) {
    Optional<HtmlMatcherGraphNode> nextNode = accNode.getNodeForEdgeKind(EdgeKind.TRUE_EDGE);
    return ImmutableList.of(visit(nextNode, exprValueMap, stack));
  }

  public void visit(HtmlMatcherGraphNode node) {
    Queue<QueuedTask> stack = new ArrayDeque<>();
    stack.add(
        visit(
            Optional.of(node), new HashMap<>(), new HtmlStack(null, foreignContentTagDepth, null)));
    while (!stack.isEmpty()) {
      QueuedTask task = stack.remove();
      List<QueuedTask> newTasks = task.run();
      stack.addAll(newTasks);
    }
  }

  private QueuedTask visit(
      Optional<HtmlMatcherGraphNode> maybeNode,
      Map<ExprEquivalence.Wrapper, Boolean> exprValueMap,
      HtmlStack stack) {
    if (!maybeNode.isPresent()) {
      return () -> {
        checkUnusedTags(stack);
        return ImmutableList.of();
      };
    }
    HtmlMatcherGraphNode node = maybeNode.get();
    if (node instanceof HtmlMatcherTagNode) {
      return () -> visit((HtmlMatcherTagNode) node, exprValueMap, stack);
    } else if (node instanceof HtmlMatcherConditionNode) {
      return () -> visit((HtmlMatcherConditionNode) node, exprValueMap, stack);
    } else if (node instanceof HtmlMatcherAccumulatorNode) {
      return () -> visit((HtmlMatcherAccumulatorNode) node, exprValueMap, stack);
    } else if (node instanceof HtmlMatcherBlockNode) {
      return () -> visit((HtmlMatcherBlockNode) node, exprValueMap, stack);
    } else {
      throw new UnsupportedOperationException("No implementation for: " + node);
    }
  }

  private void checkUnusedTags(HtmlStack stack) {
    while (!stack.isEmpty()) {
      if (stack.tagNode.getTagName().isDefinitelyOptional() && !inCondition) {
        injectCloseTag(stack.tagNode, null, idGenerator);
      } else {
        annotationMap.put(stack.tagNode, INVALID_NODE);
      }
      stack = stack.pop();
    }
  }
}
