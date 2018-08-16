/*
 * Copyright 2013 Google Inc.
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

package com.google.template.soy.soytree;

import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.template.soy.base.SourceLocation;
import com.google.template.soy.base.internal.BaseUtils;
import com.google.template.soy.base.internal.Identifier;
import com.google.template.soy.base.internal.SanitizedContentKind;
import com.google.template.soy.error.ErrorReporter;
import com.google.template.soy.soytree.TemplateNode.SoyFileHeaderInfo;
import com.google.template.soy.soytree.defn.TemplateParam;
import java.util.List;
import javax.annotation.Nullable;

/**
 * Builder for TemplateBasicNode.
 *
 * <p>Important: Do not use outside of Soy code (treat as superpackage-private).
 *
 */
public class TemplateBasicNodeBuilder extends TemplateNodeBuilder {

  /** @param soyFileHeaderInfo Info from the containing Soy file's header declarations. */
  public TemplateBasicNodeBuilder(
      SoyFileHeaderInfo soyFileHeaderInfo, ErrorReporter errorReporter) {
    super(soyFileHeaderInfo, errorReporter);
  }

  @Override
  public TemplateBasicNodeBuilder setId(int id) {
    return (TemplateBasicNodeBuilder) super.setId(id);
  }

  @Override
  public TemplateBasicNodeBuilder setSourceLocation(SourceLocation location) {
    return (TemplateBasicNodeBuilder) super.setSourceLocation(location);
  }

  @Override
  public TemplateNodeBuilder setCommandValues(
      Identifier templateName, List<CommandTagAttribute> attrs) {
    this.cmdText = templateName.identifier() + " " + Joiner.on(' ').join(attrs);
    setCommonCommandValues(attrs);

    visibility = Visibility.PUBLIC;
    for (CommandTagAttribute attribute : attrs) {
      Identifier name = attribute.getName();
      if (COMMON_ATTRIBUTE_NAMES.contains(name.identifier())) {
        continue;
      }
      switch (name.identifier()) {
        case "visibility":
          visibility = attribute.valueAsVisibility(errorReporter);
          break;
        default:
          errorReporter.report(
              name.location(),
              CommandTagAttribute.UNSUPPORTED_ATTRIBUTE_KEY,
              name.identifier(),
              "template",
              ImmutableList.builder().add("visibility").addAll(COMMON_ATTRIBUTE_NAMES).build());
      }
    }

    setTemplateNames(
        soyFileHeaderInfo.namespace + templateName.identifier(),
        templateName.identifier());
    return this;
  }

  /**
   * Alternative to {@code setCmdText()} that sets command text info directly as opposed to having
   * it parsed from the command text string. The cmdText field will be set to a canonical string
   * generated from the given info.
   *
   * @param templateName This template's name.
   * @param partialTemplateName This template's partial name. Only applicable for V2; null for V1.
   * @param visibility Visibility of this template.
   * @param autoescapeMode The mode of autoescaping for this template.
   * @param contentKind Strict mode context. Nonnull iff autoescapeMode is strict.
   * @param requiredCssNamespaces CSS namespaces required to render the template.
   * @return This builder.
   */
  public TemplateBasicNodeBuilder setCmdTextInfo(
      String templateName,
      @Nullable String partialTemplateName,
      Visibility visibility,
      AutoescapeMode autoescapeMode,
      SanitizedContentKind contentKind,
      ImmutableList<String> requiredCssNamespaces) {

    Preconditions.checkState(this.sourceLocation != null);
    Preconditions.checkState(this.cmdText == null);
    Preconditions.checkArgument(BaseUtils.isDottedIdentifier(templateName));
    Preconditions.checkArgument(
        partialTemplateName == null || BaseUtils.isIdentifierWithLeadingDot(partialTemplateName));
    Preconditions.checkArgument((contentKind != null) == (autoescapeMode == AutoescapeMode.STRICT));

    setTemplateNames(templateName, partialTemplateName);
    this.visibility = visibility;
    setAutoescapeInfo(autoescapeMode, contentKind, sourceLocation);
    setRequiredCssNamespaces(requiredCssNamespaces);

    StringBuilder cmdTextBuilder = new StringBuilder();
    cmdTextBuilder.append((partialTemplateName != null) ? partialTemplateName : templateName);
    cmdTextBuilder.append(" autoescape=\"").append(autoescapeMode.getAttributeValue()).append('"');
    if (contentKind != null) {
      cmdTextBuilder.append(" kind=\"").append(contentKind.asAttributeValue()).append('"');
    }
    // public is the default, don't generate code for it
    if (visibility != Visibility.PUBLIC) {
      cmdTextBuilder.append(" visibility=\"").append(visibility.getAttributeValue()).append("\"");
    }
    if (!requiredCssNamespaces.isEmpty()) {
      cmdTextBuilder
          .append(" requirecss=\"")
          .append(Joiner.on(", ").join(requiredCssNamespaces))
          .append("\"");
    }
    this.cmdText = cmdTextBuilder.toString();

    return this;
  }

  @Override
  public TemplateBasicNodeBuilder setSoyDoc(String soyDoc, SourceLocation soyDocLocation) {
    return (TemplateBasicNodeBuilder) super.setSoyDoc(soyDoc, soyDocLocation);
  }

  @Override
  public TemplateBasicNodeBuilder addParams(Iterable<? extends TemplateParam> allParams) {
    return (TemplateBasicNodeBuilder) super.addParams(allParams);
  }

  @Override
  public TemplateBasicNode build() {
    Preconditions.checkState(id != null && cmdText != null);
    return new TemplateBasicNode(this, soyFileHeaderInfo, visibility, params, stateVars);
  }
}
