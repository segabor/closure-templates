/*
 * Copyright 2020 Google Inc.
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

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.collect.ImmutableSet;
import com.google.template.soy.base.internal.IdGenerator;
import com.google.template.soy.error.ErrorReporter;
import com.google.template.soy.error.SoyErrorKind;
import com.google.template.soy.exprtree.ExprNode;
import com.google.template.soy.soytree.SoyFileNode;
import com.google.template.soy.soytree.SoyTreeUtils;
import com.google.template.soy.types.SoyType;

/**
 * A pass that ensures that experimental features are only used when enabled.
 *
 * <p>In general access to experimental features should be controlled by the passes that implement
 * their behavior, but if the new behavior is a change to syntax that might not be feasible, or if
 * the new behavior is a change scattered across the compiler then it might be impractical. In those
 * cases this pass is a reasonable place to put the enforcement code.
 */
final class EnforceExperimentalFeaturesPass implements CompilerFilePass {

  private static final SoyErrorKind SOY_TEMPLATE_TYPES_NOT_ALLOWED =
      SoyErrorKind.of("Soy template types are not available for general use.");

  private final ImmutableSet<String> features;
  private final ErrorReporter reporter;

  EnforceExperimentalFeaturesPass(ImmutableSet<String> features, ErrorReporter reporter) {
    this.features = checkNotNull(features);
    this.reporter = checkNotNull(reporter);
  }

  @Override
  public void run(SoyFileNode file, IdGenerator nodeIdGen) {
    if (!features.contains("soy_template_types")) {
      for (ExprNode exprNode : SoyTreeUtils.getAllNodesOfType(file, ExprNode.class)) {
        if (exprNode.getType() != null && exprNode.getType().getKind() == SoyType.Kind.TEMPLATE) {
          reporter.report(exprNode.getSourceLocation(), SOY_TEMPLATE_TYPES_NOT_ALLOWED);
        }
      }
    }
  }
}
