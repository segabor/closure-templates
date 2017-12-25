package com.google.template.soy.swiftsrc;

import java.util.Map;

import javax.annotation.Nullable;

import com.google.common.collect.ImmutableMap;

public class SoySwiftSrcOptions implements Cloneable {

  /** A namespace manifest mapping soy namespaces to their python path. */
  private final ImmutableMap<String, String> namespaceManifest;

  /** The name of a manifest file to generate, or null. */
  @Nullable private final String namespaceManifestFile;

  public SoySwiftSrcOptions(
	      ImmutableMap<String, String> namespaceManifest,
		  String namespaceManifestFile) {
	this.namespaceManifest = namespaceManifest;
    this.namespaceManifestFile = namespaceManifestFile;
  }

  public Map<String, String> getNamespaceManifest() {
    return namespaceManifest;
  }

  @Nullable
  public String namespaceManifestFile() {
    return namespaceManifestFile;
  }
}
