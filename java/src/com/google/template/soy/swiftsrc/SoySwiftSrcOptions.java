package com.google.template.soy.swiftsrc;

import javax.annotation.Nullable;

public class SoySwiftSrcOptions implements Cloneable {
  /** The name of a manifest file to generate, or null. */
  @Nullable private final String namespaceManifestFile;

  public SoySwiftSrcOptions(String namespaceManifestFile) {
    this.namespaceManifestFile = namespaceManifestFile;
  }

  private SoySwiftSrcOptions(SoySwiftSrcOptions orig) {
    this.namespaceManifestFile = orig.namespaceManifestFile;
  }

  public String namespaceManifestFile() {
    return namespaceManifestFile;
  }
}
