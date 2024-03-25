/*
 * Copyright 2022 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.devtools.mobileharness.infra.ats.server.sessionplugin;

import static com.google.devtools.mobileharness.shared.util.time.TimeUtils.toJavaDuration;
import static com.google.protobuf.TextFormat.shortDebugString;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.flogger.FluentLogger;
import com.google.common.io.Files;
import com.google.devtools.mobileharness.api.model.error.BasicErrorId;
import com.google.devtools.mobileharness.api.model.error.InfraErrorId;
import com.google.devtools.mobileharness.api.model.error.MobileHarnessException;
import com.google.devtools.mobileharness.infra.ats.common.SessionRequestHandlerUtil;
import com.google.devtools.mobileharness.infra.ats.common.proto.XtsCommonProto.XtsType;
import com.google.devtools.mobileharness.infra.ats.server.proto.ServiceProto.CancelReason;
import com.google.devtools.mobileharness.infra.ats.server.proto.ServiceProto.CommandDetail;
import com.google.devtools.mobileharness.infra.ats.server.proto.ServiceProto.CommandInfo;
import com.google.devtools.mobileharness.infra.ats.server.proto.ServiceProto.CommandState;
import com.google.devtools.mobileharness.infra.ats.server.proto.ServiceProto.NewMultiCommandRequest;
import com.google.devtools.mobileharness.infra.ats.server.proto.ServiceProto.RequestDetail;
import com.google.devtools.mobileharness.infra.ats.server.proto.ServiceProto.RequestDetail.RequestState;
import com.google.devtools.mobileharness.infra.ats.server.proto.ServiceProto.TestResource;
import com.google.devtools.mobileharness.infra.client.longrunningservice.model.SessionInfo;
import com.google.devtools.mobileharness.infra.lab.common.dir.DirUtil;
import com.google.devtools.mobileharness.shared.util.command.Command;
import com.google.devtools.mobileharness.shared.util.command.CommandExecutor;
import com.google.devtools.mobileharness.shared.util.file.local.LocalFileUtil;
import com.google.devtools.mobileharness.shared.util.path.PathUtil;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.protobuf.util.Timestamps;
import com.google.wireless.qa.mobileharness.shared.model.job.JobInfo;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import javax.inject.Inject;

/** Handler for ATS server's create test jobs request. */
final class NewMultiCommandRequestHandler {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();
  private static final String GEN_FILE_DIR = DirUtil.getPublicGenDir();

  /** Timeout setting for slow commands. */
  private static final Duration SLOW_CMD_TIMEOUT = Duration.ofMinutes(10);

  private static final Pattern ANDROID_XTS_ZIP_FILENAME_REGEX =
      Pattern.compile("android-[a-z]+\\.zip");
  @VisibleForTesting static final String XTS_TF_JOB_PROP = "xts-tradefed-job";

  private final SessionRequestHandlerUtil sessionRequestHandlerUtil;
  private final LocalFileUtil localFileUtil;
  private final CommandExecutor commandExecutor;
  private final Clock clock;

  // Cache for storing validated sessionRequestInfo for each command generated by addTradefedJobs(),
  // and mainly used by addNonTradefedJobs() to reduce duplicate sessionRequestInfo generation that
  // involves time-consuming file I/O.
  private final ConcurrentHashMap<CommandInfo, SessionRequestHandlerUtil.SessionRequestInfo>
      sessionRequestInfoCache = new ConcurrentHashMap<>();

  @Inject
  NewMultiCommandRequestHandler(
      SessionRequestHandlerUtil sessionRequestHandlerUtil,
      LocalFileUtil localFileUtil,
      CommandExecutor commandExecutor,
      Clock clock) {
    this.sessionRequestHandlerUtil = sessionRequestHandlerUtil;
    this.localFileUtil = localFileUtil;
    this.commandExecutor = commandExecutor;
    this.clock = clock;
  }

  RequestDetail addTradefedJobs(NewMultiCommandRequest request, SessionInfo sessionInfo)
      throws MobileHarnessException, InterruptedException {
    RequestDetail.Builder requestDetailBuilder =
        RequestDetail.newBuilder()
            .setCreateTime(Timestamps.fromMillis(clock.millis()))
            .setStartTime(Timestamps.fromMillis(clock.millis()))
            .setId(sessionInfo.getSessionId())
            .setState(RequestState.RUNNING)
            .setOriginalRequest(request)
            .addAllCommandInfos(request.getCommandsList());
    if (request.getCommandsList().isEmpty()) {
      requestDetailBuilder
          .setCancelReason(CancelReason.COMMAND_NOT_AVAILABLE)
          .setState(RequestState.CANCELED);
      return requestDetailBuilder.build();
    }
    for (CommandInfo commandInfo : request.getCommandsList()) {
      CommandDetail commandDetail = createXtsTradefedTestJob(request, commandInfo, sessionInfo);
      if (commandDetail.getState() == CommandState.CANCELED) {
        if (sessionRequestInfoCache.containsKey(commandInfo)
            && sessionRequestHandlerUtil.canCreateNonTradefedJobs(
                sessionRequestInfoCache.get(commandInfo))) {
          logger.atInfo().log(
              "Skip creating tradefed jobs for this command as this is a non-tradefed only"
                  + " command. Command: %s",
              commandInfo.getCommandLine());
          continue;
        }
        requestDetailBuilder
            .setCancelReason(CancelReason.INVALID_REQUEST)
            .setState(RequestState.CANCELED)
            .putCommandDetails("UNKNOWN_" + commandInfo.getCommandLine(), commandDetail);
        return requestDetailBuilder.build();
      }
      requestDetailBuilder.putCommandDetails(commandDetail.getId(), commandDetail);
    }
    requestDetailBuilder.setUpdateTime(Timestamps.fromMillis(clock.millis()));
    return requestDetailBuilder.build();
  }

  ImmutableList<CommandDetail> addNonTradefedJobs(
      NewMultiCommandRequest request, CommandInfo commandInfo, SessionInfo sessionInfo)
      throws MobileHarnessException, InterruptedException {
    SessionRequestHandlerUtil.SessionRequestInfo sessionRequestInfo;
    try {
      if (sessionRequestInfoCache.containsKey(commandInfo)) {
        sessionRequestInfo = sessionRequestInfoCache.get(commandInfo);
      } else {
        sessionRequestInfo = generateSessionRequestInfo(request, commandInfo, sessionInfo);
      }
    } catch (MobileHarnessException e) {
      logger.atWarning().withCause(e).log(
          "Failed to generate sessionRequestInfo from commandInfo: %s. SessionID: %s",
          shortDebugString(commandInfo), sessionInfo.getSessionId());
      return ImmutableList.of();
    }

    if (!sessionRequestHandlerUtil.canCreateNonTradefedJobs(sessionRequestInfo)) {
      logger.atInfo().log(
          "No valid module(s) matched, no non-tradefed jobs will run. The command info -> %s",
          shortDebugString(commandInfo));
      return ImmutableList.of();
    }

    ImmutableList<JobInfo> jobInfos =
        sessionRequestHandlerUtil.createXtsNonTradefedJobs(sessionRequestInfo);

    if (jobInfos.isEmpty()) {
      logger.atInfo().log(
          "No valid module(s) matched, no non-tradefed jobs will run. The command info -> %s",
          shortDebugString(commandInfo));
      return ImmutableList.of();
    }
    ImmutableList.Builder<CommandDetail> commandDetails = ImmutableList.builder();
    jobInfos.forEach(
        jobInfo -> {
          sessionInfo.addJob(jobInfo);
          CommandDetail commandDetail =
              CommandDetail.newBuilder()
                  .setCommandLine(commandInfo.getCommandLine())
                  .setOriginalCommandInfo(commandInfo)
                  .setId(jobInfo.locator().getId())
                  .build();
          commandDetails.add(commandDetail);
          logger.atInfo().log(
              "Added non-tradefed job[%s] to the session %s",
              jobInfo.locator().getId(), sessionInfo.getSessionId());
        });
    return commandDetails.build();
  }

  private CommandDetail createXtsTradefedTestJob(
      NewMultiCommandRequest request, CommandInfo commandInfo, SessionInfo sessionInfo)
      throws MobileHarnessException, InterruptedException {
    SessionRequestHandlerUtil.SessionRequestInfo sessionRequestInfo;
    CommandDetail.Builder commandDetailBuilder = CommandDetail.newBuilder();
    commandDetailBuilder.setCommandLine(commandInfo.getCommandLine());
    commandDetailBuilder.setOriginalCommandInfo(commandInfo);

    // Validates request and generate a sessionRequestInfo that is needed to create a jobInfo.
    try {
      sessionRequestInfo = generateSessionRequestInfo(request, commandInfo, sessionInfo);
      sessionRequestInfoCache.put(commandInfo, sessionRequestInfo);
    } catch (MobileHarnessException e) {
      if (e.getErrorId() == BasicErrorId.LOCAL_MOUNT_ZIP_TO_DIR_ERROR) {
        commandDetailBuilder
            .setState(CommandState.CANCELED)
            .setCancelReason(CancelReason.INVALID_RESOURCE);
        return commandDetailBuilder.build();
      } else {
        commandDetailBuilder
            .setState(CommandState.CANCELED)
            .setCancelReason(CancelReason.INVALID_REQUEST);
        return commandDetailBuilder.build();
      }
    }

    Optional<JobInfo> jobInfo =
        sessionRequestHandlerUtil.createXtsTradefedTestJob(sessionRequestInfo);
    if (jobInfo.isPresent()) {
      commandDetailBuilder
          .setId(jobInfo.get().locator().getId())
          .setState(CommandState.UNKNOWN_STATE);
      jobInfo.get().properties().add(XTS_TF_JOB_PROP, "true");
      sessionInfo.addJob(jobInfo.get());
      logger.atInfo().log(
          "Added job[%s] to the session %s",
          jobInfo.get().locator().getId(), sessionInfo.getSessionId());
    } else {
      commandDetailBuilder
          .setState(CommandState.CANCELED)
          .setCancelReason(CancelReason.INVALID_REQUEST);
    }
    return commandDetailBuilder.build();
  }

  private SessionRequestHandlerUtil.SessionRequestInfo generateSessionRequestInfo(
      NewMultiCommandRequest request, CommandInfo commandInfo, SessionInfo sessionInfo)
      throws MobileHarnessException, InterruptedException {
    // TODO: need to handle sharding.
    List<String> deviceSerials = new ArrayList<>();
    for (Entry<String, String> entry : commandInfo.getDeviceDimensionsMap().entrySet()) {
      if (entry.getKey().equals("device_serial")) {
        deviceSerials.add(entry.getValue());
      }
      // TODO: need to handle non device serial case.
    }
    String androidXtsZipPath = "";
    for (TestResource testResource : request.getTestResourcesList()) {
      URL testResourceUrl;
      try {
        testResourceUrl = new URL(testResource.getUrl());
      } catch (MalformedURLException e) {
        logger.atWarning().withCause(e).log(
            "Failed to parse url from url: %s", testResource.getUrl());
        continue;
      }

      if (ANDROID_XTS_ZIP_FILENAME_REGEX.matcher(testResource.getName()).matches()
          && testResourceUrl.getProtocol().equals("file")) {
        androidXtsZipPath = testResourceUrl.getPath();
        break;
      }
    }
    if (androidXtsZipPath.isEmpty()) {
      logger.atInfo().log(
          "Didn't find android xts zip file in request resources: %s, session ID: %s ",
          request.getTestResourcesList(), sessionInfo.getSessionId());
      throw new MobileHarnessException(
          InfraErrorId.ATS_SERVER_INVALID_REQUEST_ERROR,
          String.format(
              "Didn't find valid android xts zip file in request resources: %s, session ID: %s ",
              request.getTestResourcesList(), sessionInfo.getSessionId()));
    }
    String xtsRootDir =
        PathUtil.join(
            GEN_FILE_DIR,
            "session_" + sessionInfo.getSessionId(),
            Files.getNameWithoutExtension(androidXtsZipPath));
    localFileUtil.prepareDir(xtsRootDir);
    mountZip(androidXtsZipPath, xtsRootDir);
    int shardCount = commandInfo.getShardCount();
    String xtsType = Iterables.get(Splitter.on(' ').split(commandInfo.getCommandLine()), 0);
    String testPlan = xtsType;
    SessionRequestHandlerUtil.SessionRequestInfo.Builder sessionRequestInfoBuilder =
        SessionRequestHandlerUtil.SessionRequestInfo.builder();
    sessionRequestInfoBuilder.setTestPlan(testPlan);
    sessionRequestInfoBuilder.setXtsType(XtsType.valueOf(xtsType.toUpperCase(Locale.ROOT)));
    sessionRequestInfoBuilder.setXtsRootDir(xtsRootDir);
    sessionRequestInfoBuilder.setAndroidXtsZip(androidXtsZipPath);
    sessionRequestInfoBuilder.setDeviceSerials(deviceSerials);
    sessionRequestInfoBuilder.setShardCount(shardCount);

    // module argument only supports specifying one single module name.
    ImmutableList<String> commandLineTokens =
        ImmutableList.copyOf(commandInfo.getCommandLine().split(" "));
    int moduleArgIndex = commandLineTokens.indexOf("-m");
    if (moduleArgIndex != -1 && moduleArgIndex + 1 < commandLineTokens.size()) {
      sessionRequestInfoBuilder.setModuleNames(
          ImmutableList.of(commandLineTokens.get(moduleArgIndex + 1)));
    }

    // TODO: add extra args
    sessionRequestInfoBuilder.setExtraArgs(ImmutableList.of());

    // Insert timeout.
    sessionRequestInfoBuilder.setJobTimeout(
        toJavaDuration(request.getTestEnvironment().getInvocationTimeout()));
    sessionRequestInfoBuilder.setStartTimeout(toJavaDuration(request.getQueueTimeout()));

    return sessionRequestHandlerUtil.addNonTradefedModuleInfo(sessionRequestInfoBuilder.build());
  }

  /**
   * Copies xTS tradefed and non-tradefed generated logs/results into proper locations within the
   * given xts root dir.
   */
  void handleResultProcessing(
      NewMultiCommandRequest newMultiCommandRequest, SessionInfo sessionInfo)
      throws MobileHarnessException, InterruptedException {
    URL outputUrl;
    try {
      outputUrl = new URL(newMultiCommandRequest.getTestEnvironment().getOutputFileUploadUrl());
      // Currently only supports local URL.
      if (outputUrl.getProtocol().equals("file")) {
        Path outputDirPath = Path.of(outputUrl.getPath());
        String xtsType =
            Iterables.get(
                Splitter.on(' ')
                    .split(newMultiCommandRequest.getCommandsList().get(0).getCommandLine()),
                0);
        Path resultDir = outputDirPath;
        Path logDir = outputDirPath;
        sessionRequestHandlerUtil.processResult(
            XtsType.valueOf(xtsType.toUpperCase(Locale.ROOT)),
            resultDir,
            logDir,
            sessionInfo.getAllJobs());
      } else {
        logger.atWarning().log(
            "Skip processing result for unsupported file output upload url: %s",
            newMultiCommandRequest.getTestEnvironment().getOutputFileUploadUrl());
      }
    } catch (MalformedURLException e) {
      logger.atWarning().withCause(e).log(
          "Failed to parse output file upload url: %s, skip processing result.",
          newMultiCommandRequest.getTestEnvironment().getOutputFileUploadUrl());

    } catch (MobileHarnessException e) {
      logger.atWarning().withCause(e).log(
          "Failed to process result for session: %s", sessionInfo.getSessionId());
    }
    cleanup(newMultiCommandRequest, sessionInfo);
  }

  // Clean up temporary files and directories in session and jobs.
  void cleanup(NewMultiCommandRequest newMultiCommandRequest, SessionInfo sessionInfo)
      throws InterruptedException, MobileHarnessException {
    sessionRequestHandlerUtil.cleanUpJobGenDirs(sessionInfo.getAllJobs());
    for (CommandInfo commandInfo : newMultiCommandRequest.getCommandsList()) {
      String xtsRootDir = sessionRequestInfoCache.get(commandInfo).xtsRootDir();
      try {
        unmountZip(xtsRootDir);
      } catch (MobileHarnessException e) {
        logger.atWarning().withCause(e).log("Failed to unmount xts root directory: %s", xtsRootDir);
      }
    }
  }

  @CanIgnoreReturnValue
  private String mountZip(String zipFilePath, String mountDirPath)
      throws MobileHarnessException, InterruptedException {
    Command command =
        Command.of("fuse-zip", "-r", zipFilePath, mountDirPath).timeout(SLOW_CMD_TIMEOUT);
    try {
      return commandExecutor.run(command);
    } catch (MobileHarnessException e) {
      throw new MobileHarnessException(
          BasicErrorId.LOCAL_MOUNT_ZIP_TO_DIR_ERROR,
          String.format("Failed to mount zip %s into dir %s", zipFilePath, mountDirPath),
          e);
    }
  }

  @CanIgnoreReturnValue
  private String unmountZip(String mountDirPath)
      throws MobileHarnessException, InterruptedException {
    Command command = Command.of("fusermount", "-u", mountDirPath).timeout(SLOW_CMD_TIMEOUT);
    try {
      return commandExecutor.run(command);
    } catch (MobileHarnessException e) {
      throw new MobileHarnessException(
          BasicErrorId.LOCAL_UNMOUNT_DIR_ERROR,
          String.format("Failed to unmount dir %s", mountDirPath),
          e);
    }
  }
}
