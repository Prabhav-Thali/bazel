// Copyright 2024 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.devtools.build.lib.bazel.bzlmod;

import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.events.ExtendedEventHandler.Postable;

/**
Event to hold the {@link RepositoryName} of a repo that should be vendored
 */
public final class RepoToVendorEvent implements Postable {
  private final RepositoryName repositoryName;
  private RepoToVendorEvent(RepositoryName repositoryName) {
    this.repositoryName = repositoryName;
  }
  public static RepoToVendorEvent create(RepositoryName repositoryName) {
    return new RepoToVendorEvent(repositoryName);
  }
  public RepositoryName getRepositoryName() {
    return repositoryName;
  }

}
