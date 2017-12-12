package com.google.template.soy.swiftsrc.internal;

import java.util.ArrayList;
import java.util.List;

import com.google.template.soy.error.ErrorReporter;
import com.google.template.soy.soytree.AbstractSoyNodeVisitor;
import com.google.template.soy.soytree.SoyFileSetNode;
import com.google.template.soy.soytree.SoyNode;

public class GenSwiftCodeVisitor extends AbstractSoyNodeVisitor<List<String>> {
  public List<String> gen(SoyFileSetNode node, ErrorReporter errorReporter) {
    // All these fields should move into Impl but are currently exposed for tests.
//    pyCodeBuilder = null;
//    genPyExprsVisitor = null;
//    localVarExprs = null;
    return new Impl(errorReporter).exec(node);
  }

  private final class Impl extends AbstractSoyNodeVisitor<List<String>> {
    /** The contents of the generated Python files. */
    private List<String> pyFilesContents;

    final ErrorReporter errorReporter;

    Impl(ErrorReporter reporter) {
      this.errorReporter = reporter;
    }

    @Override
    public List<String> exec(SoyNode node) {
      pyFilesContents = new ArrayList<>();
      visit(node);
      return pyFilesContents;
    }
  }
}
