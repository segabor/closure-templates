package com.google.template.soy.swiftsrc;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import javax.annotation.Nullable;

import com.google.common.collect.ImmutableMap;
import com.google.template.soy.base.SourceFilePath;

public class SoySwiftSrcOptions implements Cloneable {

  /** A namespace manifest mapping soy namespaces to their python path. */
  private final ImmutableMap<String, String> namespaceManifest;

  private final ImmutableMap<SourceFilePath, Path> inputToOutputFilePaths;

  private final Optional<Path> outputDirectoryFlag;

  /** The name of a manifest file to generate, or null. */
  @Nullable
  private final String namespaceManifestFile;

  public SoySwiftSrcOptions(ImmutableMap<String, String> namespaceManifest,
      ImmutableMap<SourceFilePath, Path> inputToOutputFilePaths, Optional<Path> outputDirectoryFlag,
      String namespaceManifestFile) {
    this.namespaceManifest = namespaceManifest;
    this.inputToOutputFilePaths = inputToOutputFilePaths;
    this.outputDirectoryFlag = outputDirectoryFlag;
    this.namespaceManifestFile = namespaceManifestFile;
  }

  public Map<String, String> getNamespaceManifest() {
    return namespaceManifest;
  }

  public ImmutableMap<SourceFilePath, Path> getInputToOutputFilePaths() {
    return inputToOutputFilePaths;
  }

  /** Returns the genfiles root directory (e.g. "blaze-out/k8-opt/bin/"). */
  public Optional<Path> getOutputDirectoryFlag() {
    return outputDirectoryFlag;
  }

  @Nullable
  public String namespaceManifestFile() {
    return namespaceManifestFile;
  }
}
