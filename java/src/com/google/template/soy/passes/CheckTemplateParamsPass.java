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

package com.google.template.soy.passes;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ListMultimap;
import com.google.template.soy.base.SourceLocation;
import com.google.template.soy.base.internal.IdGenerator;
import com.google.template.soy.error.ErrorReporter;
import com.google.template.soy.error.SoyErrorKind;
import com.google.template.soy.error.SoyErrorKind.StyleAllowance;
import com.google.template.soy.error.SoyErrors;
import com.google.template.soy.exprtree.VarRefNode;
import com.google.template.soy.passes.FindIndirectParamsVisitor.IndirectParamsInfo;
import com.google.template.soy.soytree.SoyFileNode;
import com.google.template.soy.soytree.SoyTreeUtils;
import com.google.template.soy.soytree.TemplateBasicNode;
import com.google.template.soy.soytree.TemplateNode;
import com.google.template.soy.soytree.TemplateRegistry;
import com.google.template.soy.soytree.defn.TemplateParam;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map.Entry;
import java.util.Set;

/**
 * Pass for checking that in each template, the declared parameters match the data keys referenced
 * in the template.
 *
 * <p>Note this visitor only works for code in Soy V2 syntax.
 */
final class CheckTemplateParamsPass extends CompilerFileSetPass {

  private static final SoyErrorKind UNDECLARED_DATA_KEY =
      SoyErrorKind.of("Unknown data key ''{0}''.{1}", StyleAllowance.NO_PUNCTUATION);
  private static final SoyErrorKind UNUSED_PARAM =
      SoyErrorKind.of("Param ''{0}'' unused in template body.");

  private final ErrorReporter errorReporter;

  CheckTemplateParamsPass(ErrorReporter errorReporter) {
    this.errorReporter = errorReporter;
  }

  @Override
  public void run(
      ImmutableList<SoyFileNode> sourceFiles, IdGenerator idGenerator, TemplateRegistry registry) {
    for (SoyFileNode fileNode : sourceFiles) {
      for (TemplateNode templateNode : fileNode.getChildren()) {
        checkTemplate(templateNode, registry);
      }
    }
  }

  // -----------------------------------------------------------------------------------------------
  // Implementations for specific nodes.

  private void checkTemplate(TemplateNode node, TemplateRegistry templateRegistry) {
    if (node.isDeprecatedV1()) {
      return;
    }

    ListMultimap<String, SourceLocation> dataKeys = ArrayListMultimap.create();

    for (VarRefNode varRefNode : SoyTreeUtils.getAllNodesOfType(node, VarRefNode.class)) {
      if (varRefNode.isPossibleParam()) {
        dataKeys.put(varRefNode.getName(), varRefNode.getSourceLocation());
      }
    }

    IndirectParamsInfo ipi = new FindIndirectParamsVisitor(templateRegistry).exec(node);

    Set<String> allParamNames = new HashSet<>();
    List<TemplateParam> unusedParams = new ArrayList<>();
    for (TemplateParam param : node.getAllParams()) {
      allParamNames.add(param.name());
      if (dataKeys.containsKey(param.name())) {
        // Good: Declared and referenced in template. We remove these from dataKeys so
        // that at the end of the for-loop, dataKeys will only contain the keys that are referenced
        // but not declared in SoyDoc.
        dataKeys.removeAll(param.name());
      } else if (ipi.paramKeyToCalleesMultimap.containsKey(param.name())
          || ipi.mayHaveIndirectParamsInExternalCalls
          || ipi.mayHaveIndirectParamsInExternalDelCalls) {
        // Good: Declared in SoyDoc and either (a) used in a call that passes all data or (b) used
        // in an external call or delcall that passes all data, which may need the param (we can't
        // verify).
      } else {
        // Bad: Declared in SoyDoc but not referenced in template.
        unusedParams.add(param);
      }
    }

    // At this point, the only keys left in dataKeys are undeclared.
    for (Entry<String, SourceLocation> undeclared : dataKeys.entries()) {
      String extraErrorMessage = SoyErrors.getDidYouMeanMessage(allParamNames, undeclared.getKey());
      errorReporter.report(
          undeclared.getValue(), UNDECLARED_DATA_KEY, undeclared.getKey(), extraErrorMessage);
    }

    // Delegate templates can declare unused params because other implementations
    // of the same delegate may need to use those params.
    if (node instanceof TemplateBasicNode) {
      for (TemplateParam unusedParam : unusedParams) {
        errorReporter.report(unusedParam.nameLocation(), UNUSED_PARAM, unusedParam.name());
      }
    }
  }
}
