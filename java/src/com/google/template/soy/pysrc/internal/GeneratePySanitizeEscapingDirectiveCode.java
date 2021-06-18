/*
 * Copyright 2014 Google Inc.
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

package com.google.template.soy.pysrc.internal;

import com.google.common.base.CaseFormat;
import com.google.template.soy.shared.internal.AbstractGenerateSoyEscapingDirectiveCode;
import com.google.template.soy.shared.internal.DirectiveDigest;
import com.google.template.soy.shared.internal.EscapingConventions;
import com.google.template.soy.shared.internal.EscapingConventions.EscapingLanguage;
import com.google.template.soy.shared.internal.TagWhitelist;
import java.io.IOException;
import java.util.regex.Pattern;
import javax.annotation.ParametersAreNonnullByDefault;

/**
 * Generates Python code in generated_sanitize.py used by the public functions in sanitize.py.
 *
 * <p>This is an ant task and can be invoked as: <xmp> <taskdef name="gen.escape.directives"
 * classname="com.google.template.soy.pysrc.internal.GeneratePySanitizeEscapingDirectiveCode">
 * <classpath>
 * <!-- classpath to Soy classes and dependencies -->
 * </classpath> </taskdef> <gen.escape.directives> <input path="one or more Python files that use
 * the generated helpers"/> <output path="the output Python file"/> </gen.escape.directives> </xmp>
 *
 * <p>In the above, the first {@code <taskdef>} is an Ant builtin which links the element named
 * {@code <gen.escape.directives>} to this class.
 *
 * <p>That element contains zero or more {@code <input>}s which are Python source files that may use
 * the helper functions generated by this task.
 *
 * <p>There must be exactly one {@code <output>} element which specifies where the output should be
 * written. That output contains the input sources and the generated helper functions.
 */
@ParametersAreNonnullByDefault
public final class GeneratePySanitizeEscapingDirectiveCode
    extends AbstractGenerateSoyEscapingDirectiveCode {

  @Override
  protected EscapingLanguage getLanguage() {
    return EscapingLanguage.PYTHON;
  }

  @Override
  protected String getLineCommentSyntax() {
    return "#";
  }

  @Override
  protected String getLineEndSyntax() {
    return "";
  }

  @Override
  protected String getRegexStart() {
    return "re.compile(r'";
  }

  @Override
  protected String getRegexEnd() {
    return "', re.U)";
  }

  @Override
  protected String escapeOutputString(String input) {
    String escapeCharacters = "\\\'\"\b\f\n\r\t";

    // Give the string builder a little bit of extra space to account for new escape characters.
    StringBuilder result = new StringBuilder((int) (input.length() * 1.2));
    for (char c : input.toCharArray()) {
      if (escapeCharacters.indexOf(c) != -1) {
        result.append('\\');
      }
      result.append(c);
    }

    return result.toString();
  }

  @Override
  protected String convertFromJavaRegex(Pattern javaPattern) {
    String body =
        javaPattern
            .pattern()
            .replace("\r", "\\r")
            .replace("\n", "\\n")
            .replace("\u2028", "\\u2028")
            .replace("\u2029", "\\u2029")
            .replace("\\z", "\\Z");
    // DOTALL is not allowed to keep the syntax simple (it's also not available in JavaScript).
    if ((javaPattern.flags() & Pattern.DOTALL) != 0) {
      throw new IllegalArgumentException("Pattern " + javaPattern + " uses DOTALL.");
    }

    StringBuilder buffer = new StringBuilder(body.length() + 40);
    // Default to using unicode character classes.
    buffer.append("re.compile(r\"\"\"").append(body).append("\"\"\", re.U");
    if ((javaPattern.flags() & Pattern.CASE_INSENSITIVE) != 0) {
      buffer.append(" | re.I");
    }
    if ((javaPattern.flags() & Pattern.MULTILINE) != 0) {
      buffer.append(" | re.M");
    }
    buffer.append(")");
    return buffer.toString();
  }

  @Override
  protected void generatePrefix(StringBuilder outputCode) {
    // Emulate Python 3 style unicode string literals, and import necessary libraries.
    outputCode
        .append("from __future__ import unicode_literals\n")
        .append("\n")
        .append("import re\n")
        .append("\n")
        .append("try:\n")
        .append("  from urllib.parse import quote  # Python 3\n")
        .append("except ImportError:\n")
        .append("  from urllib import quote  # Python 2\n")
        .append("\n")
        .append("# An empty string which is 'str' type in Python 2 (i.e. a bytestring)\n")
        .append("# and 'str' type in Python 3 (i.e. a unicode string).\n")
        .append("ACTUALLY_STR_EMPTY_STRING = str()\n")
        .append("\n")
        .append("try:\n")
        .append("  str = unicode\n")
        .append("except NameError:\n")
        .append("  pass\n\n");
  }

  @Override
  protected void generateCharacterMapSignature(StringBuilder outputCode, String mapName) {
    outputCode.append("_ESCAPE_MAP_FOR_").append(mapName);
  }

  @Override
  protected void generateMatcher(StringBuilder outputCode, String name, String matcher) {
    outputCode.append("\n_MATCHER_FOR_").append(name).append(" = ").append(matcher).append("\n");
  }

  @Override
  protected void generateFilter(StringBuilder outputCode, String name, String filter) {
    outputCode.append("\n_FILTER_FOR_").append(name).append(" = ").append(filter).append("\n");
  }

  @Override
  protected void generateReplacerFunction(StringBuilder outputCode, String mapName) {
    String fnName = CaseFormat.UPPER_UNDERSCORE.to(CaseFormat.LOWER_UNDERSCORE, mapName);
    outputCode
        .append("\ndef _replacer_for_")
        .append(fnName)
        .append("(match):\n")
        .append("  ch = match.group(0)\n")
        .append("  return _ESCAPE_MAP_FOR_")
        .append(mapName)
        .append("[ch]\n")
        .append("\n");
  }

  @Override
  protected void useExistingLibraryFunction(
      StringBuilder outputCode, String identifier, String existingFunction) {
    String fnName = CaseFormat.LOWER_CAMEL.to(CaseFormat.LOWER_UNDERSCORE, identifier);
    // When quote is called without a second parameter, '/' is by default NOT escaped.
    // Adding an empty string as a second parameter ensures that '/' is escaped.
    // Documentation: https://docs.python.org/3/library/urllib.parse.html#urllib.parse.quote
    //                https://docs.python.org/2/library/urllib.html#urllib.quote
    outputCode
        .append("\ndef ")
        .append(fnName)
        .append("_helper(v):\n")
        .append("  return ")
        .append(existingFunction)
        .append("(str(v), ACTUALLY_STR_EMPTY_STRING)\n")
        .append("\n");
  }

  @Override
  protected void generateHelperFunction(StringBuilder outputCode, DirectiveDigest digest) {
    String name = digest.getDirectiveName();
    String fnName = CaseFormat.LOWER_CAMEL.to(CaseFormat.LOWER_UNDERSCORE, name);
    outputCode
        .append("\ndef ")
        .append(fnName)
        .append("_helper(value):\n")
        .append("  value = str(value)\n");
    if (digest.getFilterName() != null) {
      String filterName = digest.getFilterName();
      outputCode.append("  if not _FILTER_FOR_").append(filterName).append(".search(value):\n");
      // TODO(dcphillips): Raising a debugging assertion error could be useful.
      outputCode
          .append("    return '")
          .append(digest.getInnocuousOutput())
          .append("'\n")
          .append("\n");
    }

    if (digest.getNonAsciiPrefix() != null) {
      // TODO: We can add a second replace of all non-ascii codepoints below.
      throw new UnsupportedOperationException("Non ASCII prefix escapers not implemented yet.");
    }
    if (digest.getEscapesName() != null) {
      String escapeMapName =
          CaseFormat.UPPER_UNDERSCORE.to(CaseFormat.LOWER_UNDERSCORE, digest.getEscapesName());
      String matcherName = digest.getMatcherName();
      outputCode
          .append("  return _MATCHER_FOR_")
          .append(matcherName)
          .append(".sub(\n")
          .append("      _replacer_for_")
          .append(escapeMapName)
          .append(", value)\n");
    } else {
      outputCode.append("  return value\n");
    }
    outputCode.append("\n");
  }

  @Override
  protected void generateCommonConstants(StringBuilder outputCode) {
    // Emit patterns and constants needed by escaping functions that are not part of any one
    // escaping convention.
    outputCode
        .append("_HTML_TAG_REGEX = ")
        .append(convertFromJavaRegex(EscapingConventions.HTML_TAG_CONTENT))
        .append("\n\n")
        .append("_LT_REGEX = re.compile('<')\n")
        .append("\n")
        .append("_SAFE_TAG_WHITELIST = ")
        .append(toPyStringTuple(TagWhitelist.FORMATTING.asSet()))
        .append("\n\n");
  }

  /** ["foo", "bar"] -> '("foo", "bar")' */
  private String toPyStringTuple(Iterable<String> strings) {
    StringBuilder sb = new StringBuilder();
    boolean isFirst = true;
    sb.append('(');
    for (String str : strings) {
      if (!isFirst) {
        sb.append(", ");
      }
      isFirst = false;
      writeStringLiteral(str, sb);
    }
    sb.append(')');
    return sb.toString();
  }

  /** A non Ant interface for this class. */
  public static void main(String[] args) throws IOException {
    GeneratePySanitizeEscapingDirectiveCode generator =
        new GeneratePySanitizeEscapingDirectiveCode();
    generator.configure(args);
    generator.execute();
  }
}
