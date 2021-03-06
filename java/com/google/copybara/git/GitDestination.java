/*
 * Copyright (C) 2016 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.copybara.git;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.copybara.ChangeMessage.parseMessage;
import static com.google.copybara.git.LazyGitRepository.memoized;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.Iterables;
import com.google.copybara.CannotResolveRevisionException;
import com.google.copybara.ChangeMessage;
import com.google.copybara.ChangeRejectedException;
import com.google.copybara.Destination;
import com.google.copybara.GeneralOptions;
import com.google.copybara.LabelFinder;
import com.google.copybara.RepoException;
import com.google.copybara.Revision;
import com.google.copybara.TransformResult;
import com.google.copybara.ValidationException;
import com.google.copybara.git.ChangeReader.GitChange;
import com.google.copybara.git.GitRepository.GitLogEntry;
import com.google.copybara.git.GitRepository.LogCmd;
import com.google.copybara.util.DiffUtil;
import com.google.copybara.util.Glob;
import com.google.copybara.util.StructuredOutput;
import com.google.copybara.util.console.Console;
import java.io.IOException;
import java.nio.file.PathMatcher;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Nullable;

/**
 * A Git repository destination.
 */
public final class GitDestination implements Destination<GitRevision> {

  private static final String ORIGIN_LABEL_SEPARATOR = ": ";

  static class MessageInfo {

    final boolean newPush;
    final ImmutableList<LabelFinder> labelsToAdd;

    MessageInfo(ImmutableList<LabelFinder> labelsToAdd, boolean newPush) {
      this.labelsToAdd = checkNotNull(labelsToAdd);
      this.newPush = newPush;
    }
  }

  interface CommitGenerator {

    /** Generates a commit message based on the uncommitted index stored in the given repository. */
    MessageInfo message(TransformResult transformResult) throws RepoException, ValidationException;
  }

  static final class DefaultCommitGenerator implements CommitGenerator {

    @Override
    public MessageInfo message(TransformResult transformResult) {
      Revision rev = transformResult.getCurrentRevision();
      return new MessageInfo(ImmutableList.of(
          new LabelFinder(rev.getLabelName() + ORIGIN_LABEL_SEPARATOR + rev.asString())),
          /*newPush*/true);
    }
  }

  private static final Logger logger = Logger.getLogger(GitDestination.class.getName());

  private final String repoUrl;
  private final String fetch;
  private final String push;
  private final GitDestinationOptions destinationOptions;
  private final GeneralOptions generalOptions;
  // Whether the skip_push flag is set in copy.bara.sky
  private final boolean skipPush;

  private final Iterable<GitIntegrateChanges> integrates;
  // Whether skip_push is set, either by command line or copy.bara.sky
  private final boolean effectiveSkipPush;
  private final CommitGenerator commitGenerator;
  private final ProcessPushOutput processPushOutput;
  private final LazyGitRepository localRepo;

  GitDestination(
      String repoUrl,
      String fetch,
      String push,
      GitDestinationOptions destinationOptions,
      GeneralOptions generalOptions,
      boolean skipPush,
      CommitGenerator commitGenerator,
      ProcessPushOutput processPushOutput,
      Iterable<GitIntegrateChanges> integrates) {
    this.repoUrl = checkNotNull(repoUrl);
    this.fetch = checkNotNull(fetch);
    this.push = checkNotNull(push);
    this.destinationOptions = checkNotNull(destinationOptions);
    this.generalOptions = generalOptions;
    this.skipPush = skipPush;
    this.integrates = Preconditions.checkNotNull(integrates);
    this.effectiveSkipPush = skipPush || destinationOptions.skipPush;
    this.commitGenerator = checkNotNull(commitGenerator);
    this.processPushOutput = checkNotNull(processPushOutput);
    this.localRepo = memoized(ignored -> destinationOptions.localGitRepo(repoUrl));
  }

  /**
   * Throws an exception if the user.email or user.name Git configuration settings are not set. This
   * helps ensure that the committer field of generated commits is correct.
   */
  private static void verifyUserInfoConfigured(GitRepository repo)
      throws RepoException, ValidationException {
    String output = repo.simpleCommand("config", "-l").getStdout();
    boolean nameConfigured = false;
    boolean emailConfigured = false;
    for (String line : output.split("\n")) {
      if (line.startsWith("user.name=")) {
        nameConfigured = true;
      } else if (line.startsWith("user.email=")) {
        emailConfigured = true;
      }
    }
    ValidationException.checkCondition(nameConfigured && emailConfigured,
        "'user.name' and/or 'user.email' are not configured. Please run "
            + "`git config --global SETTING VALUE` to set them");
  }

  @Override
  public Writer<GitRevision> newWriter(Glob destinationFiles, boolean dryRun,
      @Nullable String groupId, @Nullable Writer<GitRevision> oldWriter) {
    WriterImpl gitOldWriter = (WriterImpl) oldWriter;

    boolean effectiveSkipPush = GitDestination.this.effectiveSkipPush || dryRun;

    WriterState state;
    if (oldWriter != null && gitOldWriter.skipPush == effectiveSkipPush) {
      state = ((WriterImpl) oldWriter).state;
    } else {
      state = new WriterState(localRepo,
          destinationOptions.localRepoPath != null
              ? push // This is nicer for the user
              : "copybara/push-" + UUID.randomUUID() + (dryRun ? "-dryrun" : ""));
    }

    return new WriterImpl<>(destinationFiles, effectiveSkipPush, repoUrl, fetch, push,
        destinationOptions, generalOptions, commitGenerator, processPushOutput,
        state, destinationOptions.nonFastForwardPush, integrates);
  }

  /**
   * State to be maintained between writer instances.
   */
  static class WriterState {

    boolean alreadyFetched;
    boolean firstWrite = true;
    final LazyGitRepository localRepo;
    final String localBranch;

    WriterState(LazyGitRepository localRepo, String localBranch) {
      this.localRepo = localRepo;
      this.localBranch = localBranch;
    }
  }

  static class WriterImpl<S extends WriterState> implements Writer<GitRevision> {

    private final Glob destinationFiles;
    final boolean skipPush;
    private final String repoUrl;
    private final String remoteFetch;
    private final String remotePush;
    private final GitDestinationOptions destinationOptions;
    private final boolean force;
    // Only use this console when you don't receive one as a parameter.
    private final Console baseConsole;
    private final GeneralOptions generalOptions;
    private final CommitGenerator commitGenerator;
    private final ProcessPushOutput processPushOutput;
    final S state;
    // We could get it from destinationOptions but this is in preparation of a GH PR destination.
    private final boolean nonFastForwardPush;
    private final Iterable<GitIntegrateChanges> integrates;

    WriterImpl(Glob destinationFiles, boolean skipPush, String repoUrl, String remoteFetch,
        String remotePush, GitDestinationOptions destinationOptions, GeneralOptions generalOptions,
        CommitGenerator commitGenerator, ProcessPushOutput processPushOutput, S state,
        boolean nonFastForwardPush, Iterable<GitIntegrateChanges> integrates) {
      this.destinationFiles = checkNotNull(destinationFiles);
      this.skipPush = skipPush;
      this.repoUrl = checkNotNull(repoUrl);
      this.remoteFetch = checkNotNull(remoteFetch);
      this.remotePush = checkNotNull(remotePush);
      this.destinationOptions = checkNotNull(destinationOptions);
      this.force = generalOptions.isForced();
      this.baseConsole = checkNotNull(generalOptions.console());
      this.generalOptions = generalOptions;
      this.commitGenerator = checkNotNull(commitGenerator);
      this.processPushOutput = checkNotNull(processPushOutput);
      this.state = checkNotNull(state);
      this.nonFastForwardPush = nonFastForwardPush;
      this.integrates = Preconditions.checkNotNull(integrates);
    }

    @Override
    public void visitChanges(@Nullable GitRevision start, ChangesVisitor visitor)
        throws RepoException, CannotResolveRevisionException {
      GitRepository repository = state.localRepo.get(baseConsole);
      try {
        fetchIfNeeded(repository, baseConsole);
      } catch (ValidationException e) {
        throw new CannotResolveRevisionException(
            "Cannot visit changes because fetch failed. Does the destination branch exist?", e);
      }
      GitRevision startRef = getLocalBranchRevision(repository);
      if (startRef == null) {
        return;
      }
      String revString = start == null ? startRef.getSha1() : start.getSha1();
      ChangeReader changeReader =
          ChangeReader.Builder.forDestination(repository, baseConsole)
              .setVerbose(generalOptions.isVerbose())
              .setLimit(1)
              .build();

      ImmutableList<GitChange> result = changeReader.run(revString);
      if (result.isEmpty()) {
        if (start == null) {
          baseConsole.error("Unable to find HEAD - is the destination repository bare?");
        }
        throw new CannotResolveRevisionException("Cannot find reference " + revString);
      }
      GitChange current = Iterables.getOnlyElement(result);
      while (current != null) {
        if (visitor.visit(current.getChange()) == VisitResult.TERMINATE
            || current.getParents().isEmpty()) {
          break;
        }
        current =
            Iterables.getOnlyElement(changeReader.run(current.getParents().get(0).getSha1()));
      }
    }

    /**
     * Do a fetch iff we haven't done one already. Prevents doing unnecessary fetches.
     */
    private void fetchIfNeeded(GitRepository repo, Console console)
        throws RepoException, ValidationException {
      if (!state.alreadyFetched) {
        GitRevision revision = fetchFromRemote(console, repo, repoUrl, remoteFetch);
        if (revision != null) {
          repo.simpleCommand("branch", state.localBranch, revision.getSha1());
        }
        state.alreadyFetched = true;
      }
    }

    @Nullable
    @Override
    public DestinationStatus getDestinationStatus(String labelName)
        throws RepoException {
      GitRepository gitRepository = state.localRepo.get(baseConsole);
      try {
        fetchIfNeeded(gitRepository, baseConsole);
      } catch (ValidationException e) {
        return null;
      }
      GitRevision startRef = getLocalBranchRevision(gitRepository);
      if (startRef == null) {
        return null;
      }

      ImmutableSet<String> roots = destinationFiles.roots();
      LogCmd logCmd = gitRepository.log(startRef.getSha1())
          .grep("^" + labelName + ORIGIN_LABEL_SEPARATOR)
          .firstParent(destinationOptions.lastRevFirstParent)
          .withPaths(Glob.isEmptyRoot(roots) ? ImmutableList.of() : roots);

      // 99% of the times it will be the first match. But grep could return a false positive
      // for a comment that contains labelName. But if entries is empty we know for sure
      // that the label is not there.
      ImmutableList<GitLogEntry> entries = logCmd.withLimit(1).run();
      if (entries.isEmpty()) {
        return null;
      }

      String value = findLabelValue(labelName, entries);
      if (value != null) {
        return new DestinationStatus(value, ImmutableList.of());
      }
      // Lets try with the latest matches. If we have that many false positives we give up.
      entries = logCmd.withLimit(50).run();
      value = findLabelValue(labelName, entries);
      if (value != null) {
        return new DestinationStatus(value, ImmutableList.of());
      }
      return null;
    }

    @Nullable
    private GitRevision getLocalBranchRevision(GitRepository gitRepository) throws RepoException {
      try {
        return gitRepository.resolveReference(state.localBranch, state.localBranch);
      } catch (CannotResolveRevisionException e) {
        if (force) {
          return null;
        }
        throw new RepoException(String.format("Could not find %s in %s and '%s' was not used",
            remoteFetch, repoUrl, GeneralOptions.FORCE));
      }
    }

    @Override
    public boolean supportsHistory() {
      return true;
    }

    @Nullable
    private String findLabelValue(String labelName, ImmutableList<GitLogEntry> entries) {
      for (GitLogEntry entry : entries) {
        List<String> prev = parseMessage(entry.getBody()).labelsAsMultimap().get(labelName);
        if (!prev.isEmpty()) {
          return Iterables.getLast(prev);
        }
      }
      return null;
    }

    @Override
    public WriterResult write(TransformResult transformResult, Console console)
        throws ValidationException, RepoException, IOException {
      logger.log(Level.INFO, "Exporting from " + transformResult.getPath() + " to: " + this);
      String baseline = transformResult.getBaseline();

      GitRepository scratchClone = state.localRepo.get(console);

      fetchIfNeeded(scratchClone, console);

      console.progress("Git Destination: Checking out " + remoteFetch);

      GitRevision localBranchRevision = getLocalBranchRevision(scratchClone);
      updateLocalBranchToBaseline(scratchClone, baseline);

      if (state.firstWrite) {
        String reference = baseline != null ? baseline : state.localBranch;
        configForPush(state.localRepo.get(console), repoUrl, remotePush);
        if (!force && localBranchRevision == null) {
          throw new RepoException(String.format(
              "Cannot checkout '%s' from '%s'. Use '%s' if the destination is a new git repo or"
                  + " you don't care about the destination current status", reference,
              repoUrl,
              GeneralOptions.FORCE));
        }
        if (localBranchRevision != null) {
          scratchClone.simpleCommand("checkout", "-f", "-q", reference);
        } else {
          // Configure the commit to go to local branch instead of master.
          scratchClone.simpleCommand("symbolic-ref", "HEAD", "refs/heads/" + state.localBranch);
        }
        state.firstWrite = false;
      } else if (!skipPush) {
        // Should be a no-op, but an iterative migration could take several minutes between
        // migrations so lets fetch the latest first.
        fetchFromRemote(console, scratchClone, repoUrl, remoteFetch);
      }


      PathMatcher pathMatcher = destinationFiles.relativeTo(scratchClone.getWorkTree());
      // Get the submodules before we stage them for deletion with
      // repo.simpleCommand(add --all)
      AddExcludedFilesToIndex excludedAdder =
          new AddExcludedFilesToIndex(scratchClone, pathMatcher);
      excludedAdder.findSubmodules(console);

      GitRepository alternate = scratchClone.withWorkTree(transformResult.getPath());

      console.progress("Git Destination: Adding all files");
      alternate.add().force().all().run();

      console.progress("Git Destination: Excluding files");
      excludedAdder.add();

      console.progress("Git Destination: Creating a local commit");
      MessageInfo messageInfo = commitGenerator.message(transformResult);

      ChangeMessage msg = ChangeMessage.parseMessage(transformResult.getSummary());
      for (LabelFinder label : messageInfo.labelsToAdd) {
        msg.addLabel(label.getName(), label.getSeparator(), label.getValue());
      }

      String commitMessage = msg.toString();
      alternate.commit(
          transformResult.getAuthor().toString(),
          transformResult.getTimestamp(),
          commitMessage);

      for (GitIntegrateChanges integrate : integrates) {
        integrate.run(alternate, generalOptions, destinationOptions, messageInfo,
            path -> !pathMatcher.matches(scratchClone.getWorkTree().resolve(path)),
            transformResult);
      }

      if (baseline != null) {
        // Our current implementation (That we should change) leaves unstaged files in the
        // work-tree. This is fine for commit/push but not for rebase, since rebase could fail
        // and needs to create a conflict resolution work-tree.
        alternate.simpleCommand("reset", "--hard");
        alternate.rebase(localBranchRevision.getSha1());
      }

      if (destinationOptions.localRepoPath != null) {

        // If the user provided a directory for the local repo we don't want to leave changes
        // in the checkout dir. Remove tracked changes:
        scratchClone.simpleCommand("reset", "--hard");
        // ...and untracked ones:
        scratchClone.simpleCommand("clean", "-f");
        scratchClone.simpleCommand("checkout", state.localBranch);
      }

      if (transformResult.isAskForConfirmation()) {
        // The git repo contains the staged changes at this point. Git diff writes to Stdout
        console.info(DiffUtil.colorize(
            console, scratchClone.simpleCommand("show", "HEAD").getStdout()));
        if (!console.promptConfirmation(
            String.format("Proceed with push to %s %s?", repoUrl, remotePush))) {
          console.warn("Migration aborted by user.");
          throw new ChangeRejectedException(
              "User aborted execution: did not confirm diff changes.");
        }
      }
      if (!skipPush) {
        console.progress(String.format("Git Destination: Pushing to %s %s", repoUrl, remotePush));
        ValidationException.checkCondition(!nonFastForwardPush
            || !Objects.equals(remoteFetch, remotePush), "non fast-forward push is only"
            + " allowed when fetch != push");

        String serverResponse = scratchClone.push()
            .withRefspecs(repoUrl, ImmutableList.of(scratchClone.createRefSpec(
                (nonFastForwardPush ? "+" : "") + "HEAD:" + remotePush)))
            .run();
        processPushOutput.process(serverResponse, messageInfo.newPush, alternate);
      }
      return WriterResult.OK;
    }

    private void updateLocalBranchToBaseline(GitRepository repo, String baseline)
        throws RepoException {
      if (baseline != null && !repo.refExists(baseline)) {
        throw new RepoException("Cannot find baseline '" + baseline
            + (getLocalBranchRevision(repo) != null
               ? "' from fetch reference '" + remoteFetch + "'"
               : "' and fetch reference '" + remoteFetch + "' itself")
            + " in " + repoUrl + ".");
      } else if (baseline != null) {
        // Update the local branch to use the baseline
        repo.simpleCommand("update-ref", state.localBranch, baseline);
      }
    }

    @Nullable
    private GitRevision fetchFromRemote(Console console, GitRepository repo, String repoUrl,
        String fetch) throws RepoException, ValidationException {
      try {
        console.progress("Git Destination: Fetching: " + repoUrl + " " + fetch);
        return repo.fetchSingleRef(repoUrl, fetch);
      } catch (CannotResolveRevisionException e) {
        String warning = String.format("Git Destination: '%s' doesn't exist in '%s'",
            fetch, repoUrl);
        if (!force) {
          throw new ValidationException(
              String.format("%s. Use %s flag if you want to push anyway", warning,
                  GeneralOptions.FORCE));
        }
        console.warn(warning);
      }
      return null;
    }

    private GitRepository configForPush(GitRepository repo, String repoUrl, String push)
        throws RepoException, ValidationException {

      if (destinationOptions.localRepoPath != null) {
        // Configure the local repo to allow pushing to the ref manually outside of Copybara
        repo.simpleCommand("config", "remote.copybara_remote.url", repoUrl);
        repo.simpleCommand("config", "remote.copybara_remote.push",
            state.localBranch + ":" + push);
        repo.simpleCommand("config", "branch." + state.localBranch
            + ".remote", "copybara_remote");
      }
      if (!Strings.isNullOrEmpty(destinationOptions.committerName)) {
        repo.simpleCommand("config", "user.name", destinationOptions.committerName);
      }
      if (!Strings.isNullOrEmpty(destinationOptions.committerEmail)) {
        repo.simpleCommand("config", "user.email", destinationOptions.committerEmail);
      }
      verifyUserInfoConfigured(repo);

      return repo;
    }

  }

  @VisibleForTesting
  String getFetch() {
    return fetch;
  }

  @VisibleForTesting
  String getPush() {
    return push;
  }

  @Override
  public String getLabelNameWhenOrigin() {
    return GitRepository.GIT_ORIGIN_REV_ID;
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this)
        .add("repoUrl", repoUrl)
        .add("fetch", fetch)
        .add("push", push)
        .add("skip_push", skipPush)
        .toString();
  }

  /**
   * Process the server response from the push command
   */
  interface ProcessPushOutput {

    /**
     * @param output - the message for the commit
     * @param newPush - true if is the first time we are pushing to the origin ref
     * @param alternateRepo - The alternate repo used for staging commits, if any
     */
    void process(String output, boolean newPush, GitRepository alternateRepo);
  }

  static class ProcessPushStructuredOutput implements ProcessPushOutput {

    protected final StructuredOutput structuredOutput;

    ProcessPushStructuredOutput(StructuredOutput output) {
      this.structuredOutput = checkNotNull(output);
    }

    @Override
    public void process(String output, boolean newPush, GitRepository alternateRepo) {
      try {
        String sha1 = alternateRepo.parseRef("HEAD");
        structuredOutput.getCurrentSummaryLineBuilder()
            .setDestinationRef(sha1)
            .setSummary(String.format("Created revision %s", sha1));
      } catch (RepoException | CannotResolveRevisionException e) {
        logger.warning(String.format("Failed setting summary: %s", e));
      }
    }
  }

  @VisibleForTesting
  LazyGitRepository getLocalRepo() {
    return localRepo;
  }

  @Override
  public ImmutableSetMultimap<String, String> describe(Glob originFiles) {
    ImmutableSetMultimap.Builder<String, String> builder =
        new ImmutableSetMultimap.Builder<String, String>()
            .put("type", "git.destination")
            .put("url", repoUrl)
            .put("fetch", fetch)
            .put("push", push);
    if (skipPush) {
      builder.put("skip_push", "" + skipPush);
    }
    return builder.build();
  }

}
