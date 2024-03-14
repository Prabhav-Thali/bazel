// Copyright 2023 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.bazel.commands;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.analysis.NoBuildEvent;
import com.google.devtools.build.lib.analysis.NoBuildRequestFinishedEvent;
import com.google.devtools.build.lib.bazel.bzlmod.BazelFetchAllValue;
import com.google.devtools.build.lib.bazel.commands.RepositoryFetcher.RepositoryFetcherException;
import com.google.devtools.build.lib.bazel.repository.RepositoryOptions;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.Reporter;
import com.google.devtools.build.lib.packages.semantics.BuildLanguageOptions;
import com.google.devtools.build.lib.pkgcache.PackageOptions;
import com.google.devtools.build.lib.rules.repository.RepositoryDelegatorFunction;
import com.google.devtools.build.lib.rules.repository.RepositoryDirectoryValue;
import com.google.devtools.build.lib.runtime.BlazeCommand;
import com.google.devtools.build.lib.runtime.BlazeCommandResult;
import com.google.devtools.build.lib.runtime.Command;
import com.google.devtools.build.lib.runtime.CommandEnvironment;
import com.google.devtools.build.lib.runtime.KeepGoingOption;
import com.google.devtools.build.lib.runtime.LoadingPhaseThreadsOption;
import com.google.devtools.build.lib.server.FailureDetails;
import com.google.devtools.build.lib.server.FailureDetails.FailureDetail;
import com.google.devtools.build.lib.server.FailureDetails.FetchCommand.Code;
import com.google.devtools.build.lib.skyframe.PrecomputedValue;
import com.google.devtools.build.lib.skyframe.RepositoryMappingValue.RepositoryMappingResolutionException;
import com.google.devtools.build.lib.util.AbruptExitException;
import com.google.devtools.build.lib.util.DetailedExitCode;
import com.google.devtools.build.lib.util.InterruptedFailureDetails;
import com.google.devtools.build.skyframe.EvaluationContext;
import com.google.devtools.build.skyframe.EvaluationResult;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import com.google.devtools.common.options.OptionsParsingResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Map.Entry;
import javax.annotation.Nullable;

/** Fetches external repositories into a specified directory. */
@Command(
    name = VendorCommand.NAME,
    options = {
      VendorOptions.class,
      PackageOptions.class,
      KeepGoingOption.class,
      LoadingPhaseThreadsOption.class
    },
    help = "resource:vendor.txt",
    shortDescription =
        "Fetches external repositories into a specific folder specified by the flag "
            + "--vendor_dir.")
public final class VendorCommand implements BlazeCommand {
  public static final String NAME = "vendor";

  @Override
  public BlazeCommandResult exec(CommandEnvironment env, OptionsParsingResult options) {
    BlazeCommandResult invalidResult = validateOptions(env, options);
    if (invalidResult != null) {
      return invalidResult;
    }

    env.getEventBus()
        .post(
            new NoBuildEvent(
                env.getCommandName(),
                env.getCommandStartTime(),
                /* separateFinishedEvent= */ true,
                /* showProgress= */ true,
                env.getCommandId().toString()));

    // IS_VENDOR_COMMAND & VENDOR_DIR is already injected in "BazelRepositoryModule", we just need
    // to update this value for the delegator function to recognize this call is from VendorCommand
    // and to invalidate cache
    env.getSkyframeExecutor()
        .injectExtraPrecomputedValues(
            ImmutableList.of(
                PrecomputedValue.injected(RepositoryDelegatorFunction.IS_VENDOR_COMMAND,
                    env.getCommandId().toString())));

    BlazeCommandResult result;
    VendorOptions vendorOptions = options.getOptions(VendorOptions.class);
    LoadingPhaseThreadsOption threadsOption = options.getOptions(LoadingPhaseThreadsOption.class);
    try {
      env.syncPackageLoading(options);
      if (!vendorOptions.repos.isEmpty()) {
        result = vendorRepos(env, threadsOption, vendorOptions.repos);
      } else {
        result = vendorAll(env, threadsOption);
      }
    } catch (AbruptExitException e) {
      return createFailedBlazeCommandResult(
          env.getReporter(), e.getMessage(), e.getDetailedExitCode());
    } catch (InterruptedException e) {
      return createFailedBlazeCommandResult(
          env.getReporter(), "Vendor interrupted: " + e.getMessage());
    }

    env.getEventBus()
        .post(
            new NoBuildRequestFinishedEvent(
                result.getExitCode(), env.getRuntime().getClock().currentTimeMillis()));
    return result;
  }

  @Nullable
  private BlazeCommandResult validateOptions(CommandEnvironment env, OptionsParsingResult options) {
    if (!options.getOptions(BuildLanguageOptions.class).enableBzlmod) {
      return createFailedBlazeCommandResult(
          env.getReporter(),
          "Bzlmod has to be enabled for vendoring to work, run with --enable_bzlmod");
    }
    if (options.getOptions(RepositoryOptions.class).vendorDirectory == null) {
      return createFailedBlazeCommandResult(
          env.getReporter(),
          Code.OPTIONS_INVALID,
          "You cannot run vendor without specifying --vendor_dir");
    }
    if (!options.getOptions(PackageOptions.class).fetch) {
      return createFailedBlazeCommandResult(
          env.getReporter(), Code.OPTIONS_INVALID, "You cannot run vendor with --nofetch");
    }
    return null;
  }

  private BlazeCommandResult vendorAll(
      CommandEnvironment env, LoadingPhaseThreadsOption threadsOption)
      throws InterruptedException {
    EvaluationContext evaluationContext =
        EvaluationContext.newBuilder()
            .setParallelism(threadsOption.threads)
            .setEventHandler(env.getReporter())
            .build();

    SkyKey fetchKey = BazelFetchAllValue.key(/* configureEnabled= */ false);
    EvaluationResult<SkyValue> evaluationResult =
        env.getSkyframeExecutor().prepareAndGet(ImmutableSet.of(fetchKey), evaluationContext);
      if (evaluationResult.hasError()) {
        Exception e = evaluationResult.getError().getException();
        return createFailedBlazeCommandResult(
            env.getReporter(),
            e != null ? e.getMessage() : "Unexpected error during fetching all external deps.");
      }
    return BlazeCommandResult.success();
  }

  private BlazeCommandResult vendorRepos(
      CommandEnvironment env,
      LoadingPhaseThreadsOption threadsOption,
      List<String> repos)
      throws InterruptedException {
    ImmutableMap<RepositoryName, RepositoryDirectoryValue> repositoryNamesAndValues;
    try {
      repositoryNamesAndValues = RepositoryFetcher.fetchRepos(repos, env, threadsOption);
    } catch (RepositoryMappingResolutionException e) {
      return createFailedBlazeCommandResult(
          env.getReporter(), "Invalid repo name: " + e.getMessage(), e.getDetailedExitCode());
    } catch (RepositoryFetcherException e) {
      return createFailedBlazeCommandResult(env.getReporter(), e.getMessage());
    }

    // Split repos to found and not found, vendor found ones and report others
    List<String> notFoundRepoErrors = new ArrayList<>();
    for (Entry<RepositoryName, RepositoryDirectoryValue> entry :
        repositoryNamesAndValues.entrySet()) {
      if (!entry.getValue().repositoryExists()) {
        notFoundRepoErrors.add(entry.getValue().getErrorMsg());
      }
    }

    if (!notFoundRepoErrors.isEmpty()) {
      return createFailedBlazeCommandResult(
          env.getReporter(), "Vendoring some repos failed with errors: " + notFoundRepoErrors);
    }
    return BlazeCommandResult.success();
  }

  private static BlazeCommandResult createFailedBlazeCommandResult(
      Reporter reporter, Code fetchCommandCode, String message) {
    return createFailedBlazeCommandResult(
        reporter,
        message,
        DetailedExitCode.of(
            FailureDetail.newBuilder()
                .setMessage(message)
                .setFetchCommand(
                    FailureDetails.FetchCommand.newBuilder().setCode(fetchCommandCode).build())
                .build()));
  }

  private static BlazeCommandResult createFailedBlazeCommandResult(
      Reporter reporter, String errorMessage) {
    return createFailedBlazeCommandResult(
        reporter, errorMessage, InterruptedFailureDetails.detailedExitCode(errorMessage));
  }

  private static BlazeCommandResult createFailedBlazeCommandResult(
      Reporter reporter, String message, DetailedExitCode exitCode) {
    reporter.handle(Event.error(message));
    return BlazeCommandResult.detailedExitCode(exitCode);
  }
}
