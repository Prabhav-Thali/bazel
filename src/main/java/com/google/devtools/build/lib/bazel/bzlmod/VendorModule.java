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

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.collect.ImmutableSet;
import com.google.common.eventbus.Subscribe;
import com.google.common.flogger.GoogleLogger;
import com.google.devtools.build.lib.bazel.commands.VendorCommand;
import com.google.devtools.build.lib.bazel.repository.RepositoryOptions;
import com.google.devtools.build.lib.cmdline.LabelConstants;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.runtime.BlazeModule;
import com.google.devtools.build.lib.runtime.CommandEnvironment;
import com.google.devtools.build.lib.util.AbruptExitException;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.Symlinks;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Module collecting the repositories fetched during this invocation and vendoring
 * them if this is vendor command
 */
public class VendorModule extends BlazeModule {

  //TODO(salmasamy) decide on name and format
  private static final String VENDOR_IGNORE = ".vendorignore";

  private Path externalPath;
  private Path vendorPath;
  private List<RepositoryName> reposToVendor = new ArrayList<>();
  private static final GoogleLogger logger = GoogleLogger.forEnclosingClass();

  @Override
  public void beforeCommand(CommandEnvironment env) {
    externalPath = env.getDirectories().getOutputBase()
        .getRelative(LabelConstants.EXTERNAL_REPOSITORY_LOCATION);

    RepositoryOptions options = env.getOptions().getOptions(RepositoryOptions.class);
    if (env.getCommand().name().equals(VendorCommand.NAME)) {
      vendorPath = options.vendorDirectory.isAbsolute()
          ? env.getWorkingDirectory().getFileSystem().getPath(options.vendorDirectory)
          : env.getWorkspace().getRelative(options.vendorDirectory);
      env.getEventBus().register(this);
    }
  }

  @Override
  public void afterCommand() throws AbruptExitException {
    if(vendorPath == null) { //this is not vendor command
      return;
    }

    try {
      if (!vendorPath.exists()) {
        vendorPath.createDirectory();
      }

      // exclude any ignored repo under .vendorignore
      Path vendorIgnore = vendorPath.getRelative(VENDOR_IGNORE);
      if (vendorIgnore.exists()) {
        ImmutableSet<String> ignoredRepos =
            ImmutableSet.copyOf(FileSystemUtils.readLines(vendorIgnore, UTF_8));
        reposToVendor =
            reposToVendor.stream()
                .filter(repo -> !ignoredRepos.contains(repo.getName()))
                .collect(toImmutableList());
      } else {
        FileSystemUtils.createEmptyFile(vendorIgnore);
      }

      // Update "out-of-date" repos under the vendor directory
      for (RepositoryName repo : reposToVendor) {
        // TODO(salmasamy) do we actually need this check? since we should only get here if the repo doesn't
        // exist under vendor or out-of-date!
        if (!isRepoUpToDate(repo.getName(), vendorPath, externalPath)) {
          Path repoUnderVendor = vendorPath.getRelative(repo.getName());
          if (!repoUnderVendor.exists()) {
            repoUnderVendor.createDirectory();
          }
          FileSystemUtils.copyTreesBelow(
              externalPath.getRelative(repo.getName()), repoUnderVendor, Symlinks.NOFOLLOW);
          FileSystemUtils.copyFile(
              externalPath.getChild("@" + repo.getName() + ".marker"),
              vendorPath.getChild("@" + repo.getName() + ".marker"));
        }
      }
    } catch (IOException e) {
      logger.atSevere().withCause(e).log(
          "Failed to vendor your repositories with error: %s.",
          e.getMessage());
    }
  }

  /**
   * Returns whether the repo under vendor needs to be updated by comparing its marker file with the
   * one under /external
   * We only get a repoToVendor if the repo does not exist under vendor directory, or exists but
   * out-of-date. In both cases, we should only get here if it was fetched or found under the
   * external cache. So, it is guaranteed that the one under external cache is up-to-date
   */
  private boolean isRepoUpToDate(String repoName, Path vendorPath, Path externalPath)
      throws IOException {
    Path vendorMarkerFile = vendorPath.getChild("@" + repoName + ".marker");
    if (!vendorMarkerFile.exists()) {
      return false;
    }
    Path externalMarkerFile = externalPath.getChild("@" + repoName + ".marker");
    String vendorMarkerContent = FileSystemUtils.readContent(vendorMarkerFile, UTF_8);
    String externalMarkerContent = FileSystemUtils.readContent(externalMarkerFile, UTF_8);
    return Objects.equals(vendorMarkerContent, externalMarkerContent);
  }

  @Subscribe
  public void fetchedReposResolved(RepoToVendorEvent repoToVendorEvent) {
    this.reposToVendor.add(repoToVendorEvent.getRepositoryName());
  }

}