/*
 * Copyright 2009 Google Inc.
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

package com.google.template.soy;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.collect.ImmutableList;
import com.google.common.io.CharSource;
import com.google.common.io.Files;
import com.google.template.soy.base.internal.BaseUtils;
import com.google.template.soy.base.internal.SoyJarFileWriter;
import com.google.template.soy.shared.internal.gencode.GeneratedFile;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import org.kohsuke.args4j.Option;

/**
 * Executable for generating Java classes containing Soy parse info.
 *
 * <p>The command-line arguments should contain command-line flags and the list of paths to the Soy
 * files.
 */
public final class SoyParseInfoGenerator extends AbstractSoyCompiler {

  @Option(
      name = "--generateInvocationBuilders",
      usage =
          "[Reqiured] Whether to generate the new java template invocation builders"
              + " (FooTemplates.java). If false, generates the old FooSoyInfo.java files"
              + " instead.")
  private boolean generateInvocationBuilders = false;

  @Option(
      name = "--outputDirectory",
      usage =
          "[Optional] The path to the output directory. If files with the same names already exist"
              + " at this location, they will be overwritten. Either --outputDirectory or"
              + " --outputJar must be set.")
  private String outputDirectory = "";

  @Option(
      name = "--outputSrcJar",
      usage =
          "[Optional]  The path to the source jar to write. If a file with the same name already"
              + " exists at this location, it will be overwritten. Either --outputDirectory or"
              + " --outputJar must be set.")
  private File outputSrcJar;

  @Option(
    name = "--javaPackage",
    required = true,
    usage = "[Required] The Java package name to use for the generated classes."
  )
  private String javaPackage = "";

  @Option(
      name = "--javaClassNameSource",
      required = true,
      usage =
          "[Required for *SoyInfo mode, Ignored for invocation builder mode (i.e. if"
              + " --generateInvocationBuilders=true)]. The source for the"
              + " generated class names. Valid values are \"filename\", \"namespace\", and"
              + " \"generic\". Option \"filename\" turns a Soy file name AaaBbb.soy or aaa_bbb.soy"
              + " into AaaBbbSoyInfo. Option \"namespace\" turns a namespace aaa.bbb.cccDdd into"
              + " CccDddSoyInfo (note it only uses the last part of the namespace). Option"
              + " \"generic\" generates class names such as File1SoyInfo, File2SoyInfo.")
  private String javaClassNameSource = "";

  SoyParseInfoGenerator(PluginLoader loader, SoyInputCache cache) {
    super(loader, cache);
  }

  SoyParseInfoGenerator() {}

  /**
   * Generates Java classes containing Soy parse info.
   *
   * <p>If syntax errors are encountered, no output is generated and the process terminates with a
   * non-zero exit status. On successful parse, the process terminates with a zero exit status.
   *
   * @param args Should contain command-line flags and the list of paths to the Soy files.
   * @throws IOException If there are problems reading the input files or writing the output file.
   */
  public static void main(final String[] args) throws IOException {
    new SoyParseInfoGenerator().runMain(args);
  }

  @Override
  protected void validateFlags() {
    // Java package is always required.
    if (javaPackage.length() == 0) {
      exitWithError("Must provide Java package.");
    }

    if (outputDirectory.isEmpty() == (outputSrcJar == null)) {
        exitWithError("Must provide exactly one of --outputDirectory or --outputSrcJar");
      }

    if (!generateInvocationBuilders && javaClassNameSource.isEmpty()) {
        exitWithError("Must provide Java class name source.");
    }
  }

  @Override
  protected void compile(SoyFileSet.Builder sfsBuilder) throws IOException {
    SoyFileSet sfs = sfsBuilder.build();

    ImmutableList<GeneratedFile> genFiles =
        generateInvocationBuilders
            ? sfs.generateInvocationBuilders(javaPackage)
            : sfs.generateParseInfo(javaPackage, javaClassNameSource);

    if (outputSrcJar == null) {
      for (GeneratedFile genFile : genFiles) {
        File outputFile = new File(outputDirectory, genFile.fileName());
        BaseUtils.ensureDirsExistInPath(outputFile.getPath());
        Files.asCharSink(outputFile, UTF_8).write(genFile.contents());
      }
    } else {
      String resourcePath = javaPackage.replace('.', '/') + "/";
      try (SoyJarFileWriter writer = new SoyJarFileWriter(new FileOutputStream(outputSrcJar))) {
        for (GeneratedFile genFile : genFiles) {
          writer.writeEntry(
              resourcePath + genFile.fileName(),
              CharSource.wrap(genFile.contents()).asByteSource(UTF_8));
        }
      }
    }
  }
}
