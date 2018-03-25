package org.javafxports.jfxmirror;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.StandardOpenOption.APPEND;
import static java.util.Locale.US;
import static org.javafxports.jfxmirror.OcaStatus.FOUND_PENDING;
import static org.javafxports.jfxmirror.OcaStatus.NOT_FOUND_PENDING;
import static org.javafxports.jfxmirror.OcaStatus.SIGNED;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Scanner;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.mail.Header;
import javax.mail.MessagingException;
import javax.mail.Session;
import javax.mail.internet.MimeMessage;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.client.Entity;
import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.Response;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.api.errors.EmtpyCommitException;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.RefSpec;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.jsoup.select.Selector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aragost.javahg.commands.ExecutionException;
import com.aragost.javahg.commands.IdentifyCommand;
import com.aragost.javahg.commands.ImportCommand;
import com.aragost.javahg.commands.UpdateCommand;
import com.aragost.javahg.commands.UpdateResult;
import com.aragost.javahg.ext.mq.StripCommand;
import com.atlassian.jira.rest.client.api.JiraRestClient;
import com.atlassian.jira.rest.client.api.JiraRestClientFactory;
import com.atlassian.jira.rest.client.api.domain.Issue;
import com.atlassian.jira.rest.client.api.domain.SearchResult;
import com.atlassian.jira.rest.client.auth.AnonymousAuthenticationHandler;
import com.atlassian.jira.rest.client.internal.async.AsynchronousJiraRestClientFactory;
import com.atlassian.util.concurrent.Promise;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.collect.Sets;

@Path("/")
public class GhEventService {

    private static final String BOT_USERNAME = "jfxmirror-bot";
    private static final java.nio.file.Path STATIC_BASE = Paths.get(System.getProperty("user.home"), "jfxmirror");
    private static final String OS_NAME = System.getProperty("os.name").toLowerCase(US);
    private static final String GITHUB_API = "https://api.github.com";
    private static final String GH_ACCEPT = "application/vnd.github.v3+json";
    private static final String GH_ACCESS_TOKEN = System.getenv("jfxmirror_gh_token");
    private static final String OCA_SEP = "@@@";
    private static final Pattern BUG_PATTERN = Pattern.compile("JDK-\\d\\d\\d\\d\\d\\d\\d");
    private static final Pattern DOUBLE_QUOTE_PATTERN = Pattern.compile("\"([^\"]*)\"");

    private static final Logger logger = LoggerFactory.getLogger(GhEventService.class);

    /**
     * Handles requests to static files from ~/jfxmirror/pr, such as ~/jfxmirror/pr/{num}/{sha}/index.html.
     * <p>
     * Assume jfxmirror_bot's HTTP server is listening on port 8433 and the URL is http://server.com. In that
     * case this endpoint is triggered when requesting an URL of the form:
     * <p>
     * {@code http://server.com:8433/pr/{some}/{path}/file.ext}
     * <p>
     * Where the path after "/pr/" can be of arbitrary depth. In the above case this method will be called with
     * {@code path = "{some}/{path}/file"} and {@code ext = "ext"} and will return (serve) the file at
     * {@code ~/jfxmirror/pr/{some}/{path}/file.ext}.
     */
    @GET
    @Path("/pr/{path:.*}.{ext}")
    public Response serveFile(@PathParam("path") String path, @PathParam("ext") String ext) {
        String contentType = "text/html";
        if (ext.equalsIgnoreCase("patch") || ext.equalsIgnoreCase("txt")) {
            contentType = "text/plain";
        }
        if (ext.equalsIgnoreCase("zip")) {
            contentType = "application/zip";
        }
        return Response.ok(STATIC_BASE.resolve("pr").resolve(path + "." + ext).toFile())
                .header("Content-Type", contentType).build();
    }

    /**
     * Handles requests to static directories from ~/jfxmirror/pr, such as ~/jfxmirror/pr/{num}/{sha} by
     * returning the "index.html" contained therein (if it exists).
     * <p>
     * Same as {@link #serveFile(String, String)} except instead of requesting a specific file, a directory
     * is requested and we act like a webserver that returns the index.html contained therein.
     */
    @GET
    @Path("/pr/{path:.*}")
    public Response serveIndex(@PathParam("path") String path) {
        return Response.ok(STATIC_BASE.resolve("pr").resolve(path).resolve("index.html").toFile())
                .header("Content-Type", "text/html").build();
    }

    /**
     * Handles incoming GitHub webhook events. This endpoint is expected to be the payload URL of the
     * webhook configured for jfxmirror_bot.
     */
    @POST
    @Path("/ghevent")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response handleGhEvent(ObjectNode event, @Context ContainerRequestContext requestContext) {
        MultivaluedMap<String, String> headers = requestContext.getHeaders();
        if (!headers.containsKey("X-GitHub-Event") || headers.get("X-GitHub-Event").size() != 1) {
            logger.error("Got POST to /pr but request did not have \"X-GitHub-Event\" header");
            return Response.status(Response.Status.BAD_REQUEST).entity(new ObjectNode(JsonNodeFactory.instance)
                    .put("error", "\"X-GitHub-Event\" header was not present or had multiple values"))
                    .type(MediaType.APPLICATION_JSON_TYPE).build();
        }

        String gitHubEvent = headers.getFirst("X-GitHub-Event");

        switch (gitHubEvent.toLowerCase(US)) {
            case "ping":
                logger.info("\u2713 Pinged by GitHub, webhook appears to be correctly configured.");
                return Response.ok().entity("pong").build();
            case "issue_comment":
                return handleComment(event);
            case "pull_request":
                final String tipBeforeImport = IdentifyCommand.on(Bot.upstreamRepo).id().rev("-1").execute();
                // Make sure to always roll the hg repository back, otherwise handling subsequent PR events will break.
                try {
                    return handlePullRequest(event, tipBeforeImport);
                } catch (Exception e) {
                    logger.error("\u2718 Encountered unexpected exception while processing pull request.");
                    logger.debug("exception: ", e);
                    rollback(tipBeforeImport);
                    resetGitRepo();
                    return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
                }
            default:
                logger.debug("Got POST to /pr but \"X-GitHub-Event\" header was not one of \"ping\", " +
                        "\"pull_request\", \"issue_comment\" but was: " + gitHubEvent);
                logger.debug("Make sure that the only checked trigger events for the jfxmirror_bot webhook are " +
                        "\"Pull request\" and \"Issue comment\"");
                return Response.status(Response.Status.BAD_REQUEST).entity(new ObjectNode(JsonNodeFactory.instance)
                        .put("error", "\"X-GitHub-Event\" header was not one of \"ping\", \"pull_request\", " +
                                "\"issue_comment\" but was: " + gitHubEvent))
                        .type(MediaType.APPLICATION_JSON_TYPE).build();
        }
    }

    /**
     * https://developer.github.com/v3/activity/events/types/#issuecommentevent
     */
    private Response handleComment(ObjectNode commentEvent) {
        String action = commentEvent.get("action").asText();

        switch (action.toLowerCase(US)) {
            case "created":
            case "edited":
                break;
            default:
                // Nothing to do.
                return Response.ok().build();
        }

        boolean commentOnPrWeCareAbout = false;
        OcaStatus ocaStatus = null;
        File[] prDirs = Paths.get(System.getProperty("user.home"), "jfxmirror", "pr")
                .toFile().listFiles(File::isDirectory);
        if (prDirs != null) {
            for (File prDir : prDirs) {
                if (prDir.getName().equals(commentEvent.get("issue").get("number").asText())) {
                    if (Files.exists(prDir.toPath().resolve(".oca"))) {
                        try {
                            String status = new String(Files.readAllBytes(prDir.toPath().resolve(".oca")), UTF_8);
                            if (status.equals(FOUND_PENDING.name().toLowerCase(US))) {
                                ocaStatus = FOUND_PENDING;
                                commentOnPrWeCareAbout = true;
                                break;
                            } else if (status.equals(NOT_FOUND_PENDING.name().toLowerCase(US))) {
                                ocaStatus = NOT_FOUND_PENDING;
                                commentOnPrWeCareAbout = true;
                                break;
                            } else if (status.equals(SIGNED.name().toLowerCase(US))) {
                                // We already know the user who opened the PR that this comment is on has signed the
                                // OCA, so we have nothing to do.
                                return Response.ok().build();
                            }
                        } catch (IOException e) {
                            logger.error("\u2718 Could not read OCA marker file.");
                            logger.debug("exception: ", e);
                        }
                    }
                }
            }
        }

        if (!commentOnPrWeCareAbout) {
            return Response.ok().build();
        }

        String issueBody = commentEvent.get("issue").get("body").asText().trim();
        if (!issueBody.startsWith("@" + BOT_USERNAME)) {
            // Not a comment for us.
            return Response.ok().build();
        }

        // 1.) @jfxmirror_bot Yes, that's me
        // 2.) @jfxmirror_bot I have signed the OCA under the name \"name\"
        // 3.) @jfxmirror_bot I have now signed the OCA using my GitHub username
        String comment = issueBody.replaceFirst("@" + BOT_USERNAME + " ", "");

        String username = commentEvent.get("comment").get("user").get("login").asText();
        String issueUrl = commentEvent.get("issue").get("url").asText();
        String reply = "@" + username + " ";
        java.nio.file.Path ocaFile = Paths.get(System.getProperty("user.home"), "jfxmirror", "oca.txt");
        java.nio.file.Path ocaMarkerFile = Paths.get(System.getProperty("user.home"), "jfxmirror", "pr",
                commentEvent.get("issue").get("number").asText(), ".oca");

        if (ocaStatus == FOUND_PENDING && comment.startsWith("Yes, that's me")) {
            reply += OcaReplies.replyWhenUserConfirmsIdentity();
            try {
                Files.write(ocaMarkerFile, SIGNED.name().toLowerCase(US).getBytes(UTF_8));
                Files.write(ocaFile, (username + OCA_SEP + username + "\n").getBytes(UTF_8), APPEND);
            } catch (IOException e) {
                logger.error("\u2718 Could not update OCA status.");
                logger.debug("exception: ", e);
                return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
            }
        } else if (comment.startsWith("I have signed the OCA under the name")) {
            // Grab the name in double quotes.
            Matcher doubleQuoteMatcher = DOUBLE_QUOTE_PATTERN.matcher(comment);
            String name = null;
            if (doubleQuoteMatcher.find()) {
                name = stripQuotes(doubleQuoteMatcher.group(0));
            } else {
                // Malformed response, did not contain a name in quotes.
                reply += OcaReplies.replyWhenNotFoundNameInQuotes(BOT_USERNAME);
            }

            try {
                boolean foundName = false;
                List<String> ocaSignatures = fetchOcaSignatures();
                for (String ocaSignature : ocaSignatures) {
                    for (String split : ocaSignature.split("-")) {
                        if (split.trim().equalsIgnoreCase(name)) {
                            foundName = true;
                            break;
                        }
                    }
                    if (foundName) {
                        break;
                    }
                }

                if (foundName) {
                    reply += OcaReplies.replyWhenFoundName(name);
                    Files.write(ocaMarkerFile, SIGNED.name().toLowerCase(US).getBytes(UTF_8));
                    Files.write(ocaFile, (username + OCA_SEP + name + "\n").getBytes(UTF_8), APPEND);
                } else {
                    reply += OcaReplies.replyWhenNotFoundName(name, BOT_USERNAME);
                }
            } catch (IOException e) {
                logger.error("\u2718 Could not download OCA signatures page.");
                logger.debug("exception: ", e);
                return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
            }
        } else if (comment.startsWith("I have now signed the OCA using my GitHub username")) {
            try {
                boolean foundUsername = false;
                List<String> ocaSignatures = fetchOcaSignatures();
                for (String ocaSignature : ocaSignatures) {
                    for (String split : ocaSignature.split("-")) {
                        if (split.trim().equalsIgnoreCase(username)) {
                            foundUsername = true;
                            break;
                        }
                    }
                    if (foundUsername) {
                        break;
                    }
                }

                if (foundUsername) {
                    reply += OcaReplies.replyWhenFoundUsername();
                    Files.write(ocaMarkerFile, SIGNED.name().toLowerCase(US).getBytes(UTF_8));
                    Files.write(ocaFile, (username + OCA_SEP + username + "\n").getBytes(UTF_8), APPEND);
                } else {
                    reply += OcaReplies.replyWhenNotFoundUsername(username, BOT_USERNAME);
                }
            } catch (IOException e) {
                logger.error("\u2718 Could not download OCA signatures page.");
                logger.debug("exception: ", e);
                return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
            }
        } else {
            // Can't understand the response
            reply += OcaReplies.replyWhenCantUnderstandResponse();
        }

        Response commentResponse = Bot.httpClient.target(issueUrl)
                .request()
                .header("Authorization", "token " + GH_ACCESS_TOKEN)
                .accept(GH_ACCEPT)
                .post(Entity.json(JsonNodeFactory.instance.objectNode().put("body", reply)));

        if (commentResponse.getStatus() == 404) {
            logger.error("\u2718 Could not post comment on PR #" + commentEvent.get("issue").get("number"));
            logger.debug("GitHub response: " + commentResponse.readEntity(String.class));
            return Response.status(Response.Status.BAD_REQUEST).build();
        }
        return Response.ok().build();
    }

    /**
     * https://developer.github.com/v3/activity/events/types/#pullrequestevent
     */
    private Response handlePullRequest(ObjectNode pullRequestEvent, String tipBeforeImport) {
        String action = pullRequestEvent.get("action").asText();

        // "assigned", "unassigned", "review_requested", "review_request_removed", "labeled", "unlabeled", "opened",
        // "edited", "closed", or "reopened"
        switch (action.toLowerCase(US)) {
            case "opened":
            case "edited":
            case "reopened":
                break;
            default:
                // Nothing to do.
                return Response.ok().build();
        }

        JsonNode pullRequest = pullRequestEvent.get("pull_request");
        String prNum = pullRequest.get("number").asText();
        String prShaHead = pullRequest.get("head").get("sha").asText();
        logger.debug("Event: Pull request #" + prNum + " " + action);

        String[] repoFullName = pullRequestEvent.get("repository").get("full_name").asText().split("/");
        String statusUrl = String.format("%s/repos/%s/%s/statuses/%s", GITHUB_API,
                repoFullName[0], repoFullName[1], prShaHead);

        // Set the status of the PR to pending while we do the necessary checks.
        setPrStatus(PrStatus.PENDING, prNum, prShaHead, statusUrl, "Checking for upstream mergeability...", null);

        // TODO: Because some files exist only in the downstream GitHub repository and not the upstream hg repository,
        // we should exclude all changes to those files so that the patch applies cleanly. We can probably make a
        // black-list of files to exclude, which would include the README, .github, .ci, appveyor.yml, .travis.yml, etc.
        // One way to do this would be to git checkout the HEAD of the pull request, perform a git reset --mixed,
        // and only add back the unstaged files that are not in our blacklist (which includes the files mentioned before).
        // Then we could re-commit, and use that for our git format-patch base.

        // TODO: Before converting the PR patch, should it be squashed to one commit of concatenated commit messages?
        // Currently we lose the commit messages of all but the first commit. They are separated in the original
        // patch file, like [1/3], [2/3], [3/3].
        String patchUrl = pullRequest.get("patch_url").asText();
        String hgPatch;
        try {
            hgPatch = convertGitPatchToHgPatch(patchUrl);
        } catch (IOException | MessagingException e) {
            setPrStatus(PrStatus.ERROR, prNum, prShaHead, statusUrl, "Could not convert git patch to hg patch.", null);
            logger.error("\u2718 Encountered error trying to convert git patch to hg patch.");
            logger.debug("exception: ", e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
        }

        String patchFilename = patchUrl.substring(patchUrl.lastIndexOf('/') + 1);
        java.nio.file.Path patchDir = Paths.get(System.getProperty("user.home"), "jfxmirror", "pr", prNum, prShaHead, "patch");
        if (!Files.exists(patchDir)) {
            try {
                logger.debug("Creating directory: " + patchDir);
                Files.createDirectories(patchDir);
            } catch (IOException e) {
                setPrStatus(PrStatus.ERROR, prNum, prShaHead, statusUrl, "Could not create patches directory.", null);
                logger.error("\u2718 Could not create patches directory: " + patchDir);
                logger.debug("exception: ", e);
                return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
            }
        }
        java.nio.file.Path hgPatchPath = patchDir.resolve(patchFilename);
        try {
            logger.debug("Writing hg patch: " + hgPatchPath);
            Files.write(hgPatchPath, hgPatch.getBytes(UTF_8));
        } catch (IOException e) {
            setPrStatus(PrStatus.ERROR, prNum, prShaHead, statusUrl, "Could not write hg patch to file.", null);
            logger.error("\u2718 Could not write hg patch to file.");
            logger.debug("exception: ", e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
        }

        // TODO: Technically we don't want to import the hg patch with the upstream repository at the latest commit
        // but instead want it to be at the latest commit that has been mirrored to the GitHub repository. To take this
        // into account is kind of tricky because we would have to read the GitHub commit log, look for the latest
        // "Merge from (root)" commit by "javafxports-github-bot", and then reset the upstream repo to the commit
        // before that. If conflicts become a recurring problem this is something we can tackle later on.
        // FIXME: Actually no, we need to tackle this because as it stands the upstream hg repository is never updated!
        // List<RevCommit> lastNCommits = new ArrayList<>();

        // Find the most recent merge commit from "javafxports-github-bot" so that we can use it to sync the upstream
        // hg repository so that the exported git patch and imported hg patch are referencing the same repository state.

        Git git = new Git(Bot.mirrorRepo);
        RevCommit latestMergeCommit = null;

        try {
            Iterable<RevCommit> commits = git.log().all().call();
            for (RevCommit commit : commits) {
                if (commit.getAuthorIdent().getName().equalsIgnoreCase("javafxports-github-bot") &&
                        commit.getShortMessage().equalsIgnoreCase("Merge from (root)")) {
                    latestMergeCommit = commit;
                    break;
                }
            }
        } catch (IOException | GitAPIException e) {
            e.printStackTrace();
        }

        if (latestMergeCommit == null) {
            setPrStatus(PrStatus.ERROR, prNum, prShaHead, statusUrl, "Could not find previous merge commit.", null);
            logger.error("\u2718 Could not find previous merge commit.");
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
        }

        logger.info("\u2713 Found latest merge commit: " + Arrays.toString(latestMergeCommit.getParents()));
        RevCommit mostRecentUpstreamCommit = null;
        for (RevCommit commit : latestMergeCommit.getParents()) {
            if (!commit.getAuthorIdent().getName().equalsIgnoreCase("javafxports-github-bot") &&
                    !commit.getShortMessage().contains("Merge")) {
                mostRecentUpstreamCommit = commit;
                break;
            }
        }

        if (mostRecentUpstreamCommit == null) {
            setPrStatus(PrStatus.ERROR, prNum, prShaHead, statusUrl, "Could not determine latest upstream commit in mirror.", null);
            logger.error("\u2718 Could not determine latest upstream commit in mirror.");
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
        }

        // At this point we know that "mostRecentUpsteamCommit" is the latest commit from upstream to be merged into
        // the git mirror repository. Thus we use that for our head for constructing the git patch. But first we
        // need to remove any "blacklisted" files from the commit (files that are only present on the mirror, and never
        // on upstream).
        try {
            // Fetch/ checkout pull request.
            String requestBranch = pullRequest.get("head").get("ref").asText();
            git.fetch().setRemote("origin").setRefSpecs(new RefSpec(
                    "refs/heads/" + requestBranch + ":refs/pull/" + prNum + "/head")).call();
            git.checkout().setName(requestBranch).call();
            Ref head = Bot.mirrorRepo.exactRef("refs/heads/master");
            RevWalk walk = new RevWalk(Bot.mirrorRepo);
            RevCommit commit = walk.parseCommit(head.getObjectId());
            walk.markStart(commit);
            int count = 0;
            for (RevCommit rev : walk) {
                logger.debug("Commit: " + rev.getShortMessage());
                count++;
            }

            walk.dispose();

            // Squash all commits into 1 ... then:
            RevCommit latestCommitOfPr = git.log().setMaxCount(1).call().iterator().next();
            git.reset().setMode(ResetCommand.ResetType.SOFT).setRef(Bot.mirrorRepo.resolve("HEAD^").getName()).call();
            git.reset().setRef(Bot.mirrorRepo.resolve("HEAD").getName()).addPath(".travis.yml").call();
            git.reset().setRef(Bot.mirrorRepo.resolve("HEAD").getName()).addPath("README.md").call();
            git.reset().setRef(Bot.mirrorRepo.resolve("HEAD").getName()).addPath("appveyor.yml").call();
            git.reset().setRef(Bot.mirrorRepo.resolve("HEAD").getName()).addPath(".github/**").call();
            git.reset().setRef(Bot.mirrorRepo.resolve("HEAD").getName()).addPath(".ci/**").call();

            try {
               git.commit().setMessage(latestCommitOfPr.getFullMessage())
                       .setAuthor(latestCommitOfPr.getAuthorIdent())
                       .setAllowEmpty(false).call();
            } catch (EmtpyCommitException e) {
                // If the commit is empty that means this PR only touches blacklisted files, so it has no intention
                // of being merged to upstream, so we can stop now.
                setPrStatus(PrStatus.SUCCESS, prNum, prShaHead, statusUrl,
                        "PR has no changes meant for upstream.", tipBeforeImport);
                return Response.ok().build();
            }
        } catch (GitAPIException | IOException e) {
            e.printStackTrace();
        }

        // Now the PR is made up of one squashed commit, and blacklisted files are removed. So now we want to generate
        // a patch between "mostRecentUpstreamCommit" and the one-commit PR (using git-format patch).
        System.exit(0);

        // Apply the hg patch to the upstream hg repo
        logger.debug("Fetching tip revision before import...");
        logger.debug("Tip revision before import: " + tipBeforeImport);
        try {
            ImportCommand importCommand = ImportCommand.on(Bot.upstreamRepo);
            // importCommand.cmdAppend("--no-commit");
            importCommand.execute(hgPatchPath.toFile());
            logger.debug("Updating upstream hg repository...");
            // TODO: Could skip this by using `--bypass` argument to importCommand?
            UpdateResult updateResult = UpdateCommand.on(Bot.upstreamRepo).execute();
        } catch (IOException | ExecutionException e) {
            setPrStatus(PrStatus.FAILURE, prNum, prShaHead, statusUrl,
                    "Could not apply PR changeset to upstream hg repository.", tipBeforeImport);
            logger.error("\u2718 Could not apply PR changeset to upstream mercurial repository.");
            logger.debug("exception: ", e);
            // FIXME: Uncomment this after testing
            // return Response.status(Response.Status.BAD_REQUEST).build();
        }
        String previousCommit = IdentifyCommand.on(Bot.upstreamRepo).id().rev("-2").execute();
        logger.debug("Previous commit (after import): " + previousCommit);
        if (!previousCommit.equals(tipBeforeImport)) {
            logger.error("\u2718 The tip before importing is not equal to the previous commit!");
            logger.debug("This can happen if the hg repository was not rolled back after importing GitHub PR.");
            logger.debug("This requires manual intervention - the hg repository must be rolled back.");
            setPrStatus(PrStatus.FAILURE, prNum, prShaHead, statusUrl, "Upstream hg repository error.", null);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
        }

        // If necessary, check if user who opened PR has signed the OCA http://www.oracle.com/technetwork/community/oca-486395.html
        String username = pullRequest.get("user").get("login").asText();
        java.nio.file.Path ocaMarkerFile = Paths.get(System.getProperty("user.home"), "jfxmirror", "pr", prNum, ".oca");
        OcaStatus ocaStatus = NOT_FOUND_PENDING;
        if (Files.exists(ocaMarkerFile)) {
            try {
                String ocaMarkerContents = new String(Files.readAllBytes(ocaMarkerFile), StandardCharsets.UTF_8);
                ocaStatus = OcaStatus.valueOf(ocaMarkerContents.toUpperCase(Locale.US));
            } catch (IOException e) {
                setPrStatus(PrStatus.ERROR, prNum, prShaHead, statusUrl,
                        "Could not read OCA marker file.", tipBeforeImport);
                logger.error("\u2718 Could not read OCA marker file: " + ocaMarkerFile);
                logger.debug("exception: ", e);
                return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
            }
            logger.debug("Already checked if \"" + username + "\" has signed the OCA.");
        } else {
            logger.debug("Checking if \"" + username + "\" has signed the OCA...");
            java.nio.file.Path ocaFile = Paths.get(System.getProperty("user.home"), "jfxmirror", "oca.txt");
            String ocaName;
            try {
                List<String> ocaFileLines = Files.readAllLines(ocaFile, UTF_8);
                for (String line : ocaFileLines) {
                    String[] gitHubUsernameOcaName = line.split(OCA_SEP);
                    if (gitHubUsernameOcaName.length != 2) {
                        setPrStatus(PrStatus.ERROR, prNum, prShaHead, statusUrl,
                                "OCA signature file malformed.", tipBeforeImport);
                        logger.error("\u2718 OCA signature file malformed (expecting separator \"" + OCA_SEP + "\").");
                        logger.debug("Bad line: " + line);
                        return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
                    }

                    if (gitHubUsernameOcaName[0].equals(username)) {
                        ocaName = gitHubUsernameOcaName[1];
                        logger.info("\u2713 User who opened PR is known to have signed OCA under the name: " + ocaName);
                        // Write a marker file for OCA status.
                        Files.write(ocaMarkerFile, SIGNED.name().toLowerCase(US).getBytes(UTF_8));
                        ocaStatus = SIGNED;
                        break;
                    }
                }
            } catch (IOException e) {
                setPrStatus(PrStatus.ERROR, prNum, prShaHead, statusUrl,
                        "Could not read OCA signatures file.", tipBeforeImport);
                logger.error("\u2718 Could not read OCA signatures file.");
                logger.debug("exception: ", e);
                return Response.status(Response.Status.BAD_REQUEST).build();
            }

            if (ocaStatus != SIGNED) {
                // We have no record that the user who opened this PR signed the OCA so let's check Oracle's OCA
                // page to see if we can find their username.
                boolean foundUsername = false;
                String ocaLine = null;
                try {
                    List<String> ocaSignatures = fetchOcaSignatures();
                    for (String ocaSignature : ocaSignatures) {
                        // FIXME: This is a really imperfect way to do this, but it was the best I could think of quickly.
                        for (String split : ocaSignature.split(" - ")) {
                            if (split.equalsIgnoreCase(username)) {
                                foundUsername = true;
                                ocaLine = ocaSignature;
                                break;
                            }
                        }
                    }
                } catch (IOException e) {
                    setPrStatus(PrStatus.ERROR, prNum, prShaHead, statusUrl,
                            "Could not download OCA signatures page.", tipBeforeImport);
                    logger.error("\u2718 Could not download OCA signatures page.");
                    logger.debug("exception: ", e);
                    return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
                }

                String commentsUrl = pullRequest.get("_links").get("comments").get("href").asText();
                String comment = "@" + username + " ";
                if (foundUsername) {
                    try {
                        Files.write(ocaMarkerFile, FOUND_PENDING.name().toLowerCase(US).getBytes(UTF_8));
                        ocaStatus = FOUND_PENDING;
                    } catch (IOException e) {
                        logger.error("\u2718 Could not write OCA marker file.");
                        logger.debug("exception: ", e);
                    }

                    logger.debug("Found GitHub username of user who opened PR on OCA signature list.");
                    comment += OcaComments.commentWhenFoundUsername(ocaLine, BOT_USERNAME);
                } else {
                    try {
                        Files.write(ocaMarkerFile, NOT_FOUND_PENDING.name().toLowerCase(US).getBytes(UTF_8));
                        ocaStatus = NOT_FOUND_PENDING;
                    } catch (IOException e) {
                        logger.error("\u2718 Could not write OCA marker file.");
                        logger.debug("exception: ", e);
                    }
                    // Post comment on PR telling them we could not find their github username listed on OCA signature page,
                    // ask them if they have signed it.
                    comment += OcaComments.commentWhenNotFoundUsername();
                }
                comment += OcaComments.defaultComment(BOT_USERNAME);

                Response commentResponse = Bot.httpClient.target(commentsUrl)
                        .request()
                        .header("Authorization", "token " + GH_ACCESS_TOKEN)
                        .accept(GH_ACCEPT)
                        .post(Entity.json(JsonNodeFactory.instance.objectNode().put("body", comment).toString()));

                if (commentResponse.getStatus() == 404) {
                    logger.error("\u2718 Could not post comment on PR #" + prNum + " for assisting the user who " +
                            "opened the PR with confirming their signing of the OCA.");
                    logger.debug("GitHub response: " + commentResponse.readEntity(String.class));
                    setPrStatus(PrStatus.ERROR, prNum, prShaHead, statusUrl,
                            "Could not post comment to PR.", tipBeforeImport);
                    return Response.status(Response.Status.BAD_REQUEST).build();
                }
            }
        }

        // See if there is a JBS bug associated with this PR. This is accomplished by checking if any of the following
        // places contain the text "JDK-xxxxxxx" where x is some integer:
        // 1.) Each commit message of the commits that make up this PR.
        // 2.) The PR title.
        // 3.) The branch name of this PR.
        logger.debug("Checking if this PR is associated with any JBS bugs...");
        Set<String> jbsBugsReferenced = new HashSet<>();

        // Check if any commit messages of this PR contain a JBS bug (like JDK-xxxxxxx).
        String commitsUrl = pullRequest.get("_links").get("commits").get("href").asText();
        Response commitsResponse = Bot.httpClient.target(commitsUrl + "?per_page=250")
                .request()
                .header("Authorization", "token " + GH_ACCESS_TOKEN)
                .accept(GH_ACCEPT)
                .get();

        try {
            JsonNode commitsJson = new ObjectMapper().readTree(commitsResponse.readEntity(String.class));
            for (JsonNode commitJson : commitsJson) {
                String commitMessage = commitJson.get("commit").get("message").asText();
                Matcher bugPatternMatcher = BUG_PATTERN.matcher(commitMessage);
                if (bugPatternMatcher.find()) {
                    jbsBugsReferenced.add(bugPatternMatcher.group(0));
                }
            }
        } catch (IOException e) {
            logger.error("\u2718 Could not read commits JSON.");
            logger.debug("exception: ", e);
            setPrStatus(PrStatus.ERROR, prNum, prShaHead, statusUrl, "Could not read commits JSON.", tipBeforeImport);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
        }

        // Check if the branch name of the PR contains a JBS bug.
        String prBranchName = pullRequest.get("head").get("ref").asText();
        Matcher bugMatcher = BUG_PATTERN.matcher(prBranchName);
        if (bugMatcher.find()) {
            jbsBugsReferenced.add(bugMatcher.group(0));
        }

        // Check if the PR title contains a JBS bug.
        String prTitle = pullRequest.get("title").asText();
        bugMatcher = BUG_PATTERN.matcher(prTitle);
        if (bugMatcher.find()) {
            jbsBugsReferenced.add(bugMatcher.group(0));
        }

        Set<String> foundJbsBugs = new HashSet<>();
        // FIXME: Cache the client
        JiraRestClientFactory factory = new AsynchronousJiraRestClientFactory();
        JiraRestClient jiraRestClient = factory.create(URI.create("https://bugs.openjdk.java.net"),
                new AnonymousAuthenticationHandler());
        for (String jbsBug : jbsBugsReferenced) {
            Promise<SearchResult> searchJqlPromise = jiraRestClient.getSearchClient().searchJql(
                    "project = JDK AND status IN ('Open', 'In Progress', 'New', 'Provisional') AND component = javafx AND id = " + jbsBug);
            Set<Issue> issues = Sets.newHashSet(searchJqlPromise.claim().getIssues());
            if (!issues.isEmpty()) {
                foundJbsBugs.add(jbsBug);
            }
        }

        Set<String> jbsBugsReferencedButNotFound = Sets.difference(jbsBugsReferenced, foundJbsBugs);

        // Run jcheck http://openjdk.java.net/projects/code-tools/jcheck/
        logger.debug("Running jcheck on PR #" + prNum + " (" + prShaHead + ")...");
        java.nio.file.Path jcheckOutputPath = Paths.get(System.getProperty("user.home"), "jfxmirror", "pr",
                prNum, prShaHead, "jcheck.txt");
        try {
            Files.write(jcheckOutputPath, "".getBytes(), StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        } catch (IOException e) {
            logger.error("\u2718 Could not run jcheck.");
            logger.debug("exception: ", e);
            setPrStatus(PrStatus.ERROR, prNum, prShaHead, statusUrl, "Could not run jcheck.", tipBeforeImport);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
        }
        ProcessBuilder jcheckBuilder = new ProcessBuilder("hg", "jcheck")
                .directory(Bot.upstreamRepo.getDirectory())
                .redirectError(jcheckOutputPath.toFile())
                .redirectOutput(jcheckOutputPath.toFile());

        try {
            Process jcheck = jcheckBuilder.start();
            jcheck.waitFor(1, TimeUnit.MINUTES);
        } catch (IOException | InterruptedException e) {
            logger.error("\u2718 Could not run jcheck.");
            logger.debug("exception: ", e);
            setPrStatus(PrStatus.ERROR, prNum, prShaHead, statusUrl, "Could not run jcheck.", tipBeforeImport);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
        }

        // This runs jcheck by using a javahg mercurial extension. The problem is that the output is not captured
        // via getErrorString(), so it is useless for our purposes. It would be much nicer to use than a raw hg process
        // as we do currently.
        /*
        GenericCommand jcheckCommand = new GenericCommand(Bot.upstreamRepo, "jcheck");
        String jcheckResults = null;
        try {
            jcheckResults = jcheckCommand.execute();
            if (jcheckCommand.getReturnCode() == 0) {
                jcheckResults = "Success - no warnings from jcheck.";
            }
        } catch (ExecutionException e) {
            if (jcheckCommand.getReturnCode() == 1) {
                // Expected error, this means jcheck failed.
                logger.debug("jcheck error string: " + jcheckCommand.getErrorString());
                jcheckResults = jcheckCommand.getErrorString();
            } else {
                logger.error("\u2718 Unexpected exit code from jcheck: " + jcheckCommand.getReturnCode());
                logger.debug("exception: ", e);
                setPrStatus(PrStatus.ERROR, prNum, prShaHead, statusUrl, "Unexpected exit code from jcheck.");
                return Response.status(Response.Status.BAD_REQUEST).build();
            }
        }
        */

        // Generate a webrev
        java.nio.file.Path webRevOutputPath = Paths.get(System.getProperty("user.home"),
                "jfxmirror", "pr", prNum, prShaHead);
        logger.debug("Will generate webrev against revision: " + previousCommit);
        ProcessBuilder webrevBuilder;
        if (OS_NAME.contains("windows")) {
            // Calling ksh to generate webrev requires having bash in the Windows %PATH%, this works on e.g. WSL.
            String kshInvocation = "\"ksh " +
                    Paths.get(System.getProperty("user.home"), "jfxmirror", "webrev", "webrev.ksh").toString()
                            .replaceFirst("C:\\\\", "/mnt/c/").replaceAll("\\\\", "/")
                    + " -r " + previousCommit + " -N -m -o " + webRevOutputPath.toString()
                    .replaceFirst("C:\\\\", "/mnt/c/").replaceAll("\\\\", "/") + "\"";
            logger.info("invocation: " + kshInvocation);
            webrevBuilder = new ProcessBuilder("bash", "-c", kshInvocation);
        } else {
            // Just call ksh directly.
            webrevBuilder = new ProcessBuilder("ksh",
                    Paths.get(System.getProperty("user.home"), "jfxmirror", "webrev", "webrev.ksh").toString(),
                    "-N", "-m",
                    "-o", webRevOutputPath.toString());
        }
        // TODO: Add -c argument for the bug ID when we implement JBS bugs
        webrevBuilder.directory(Bot.upstreamRepo.getDirectory());
        try {
            webrevBuilder.inheritIO();
            logger.debug("Generating webrev for PR #" + prNum + " (" + prShaHead + ")...");
            Process webrev = webrevBuilder.start();
            boolean webrevFinished = webrev.waitFor(2, TimeUnit.MINUTES);
            if (!webrevFinished) {
                setPrStatus(PrStatus.ERROR, prNum, prShaHead, statusUrl,
                        "Could not generate webrev in time.", tipBeforeImport);
                logger.error("\u2718 Could not generate webrev in time.");
                return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
            }
        } catch (SecurityException | IOException | InterruptedException e) {
            setPrStatus(PrStatus.ERROR, prNum, prShaHead, statusUrl,
                    "Could not generate webrev for PR.", tipBeforeImport);
            logger.error("\u2718 Encountered error trying to generate webrev.");
            logger.debug("exception: ", e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
        }

        // Make status page at "pr/{prNum}/{prShaHead}" from the above data, set status to success/fail.
        java.nio.file.Path statusPath = Paths.get(System.getProperty("user.home"), "jfxmirror", "pr", prNum, prShaHead);
        if (!Files.exists(statusPath)) {
            try {
                Files.createDirectories(statusPath);
            } catch (IOException e) {
                logger.error("\u2718 Could not create directory: " + statusPath);
                logger.debug("exception: ", e);
            }
        }

        // Create status index page (that is linked to by the jfxmirror_bot PR status check.
        String statusPage = StatusPage.getStatusPageHtml(prNum, prShaHead, ocaStatus,
                jbsBugsReferenced, jbsBugsReferencedButNotFound);
        try {
            Files.write(statusPath.resolve("index.html"), statusPage.getBytes(UTF_8));
        } catch (IOException e) {
            logger.error("\u2718 Could not write \"index.html\" to: " + statusPath);
            logger.debug("exception: ", e);
            setPrStatus(PrStatus.ERROR, prNum, prShaHead, statusUrl, "Could not write status page.", tipBeforeImport);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
        }

        // Rollback upstream hg repository back to tipBeforeImport.
        // TODO: Instead of doing this, we could create a temporary branch to work on before importing the GH PR.
        rollback(tipBeforeImport);

        // If we get this far, then we can set PR status to success.
        setPrStatus(PrStatus.SUCCESS, prNum, prShaHead, statusUrl, "Ready to merge with upstream.", tipBeforeImport);

        return Response.ok().build();
    }

    /**
     * Rollback the upstream mercurial repository iff the given {@code tipToRollbackTo} is the previous tip
     * before importing.
     */
    private static void rollback(String tipToRollbackTo) {
        String tipMinusOne = IdentifyCommand.on(Bot.upstreamRepo).id().rev("-2").execute();
        if (tipMinusOne.equals(tipToRollbackTo)) {
            logger.debug("Rolling mercurial back to rev before patch import...");
            try {
                StripCommand.on(Bot.upstreamRepo).rev("-1").noBackup().execute();
            } catch (Exception e) {
                logger.debug("exception: ", e);
            }
        }

    }

    private static void resetGitRepo() {
        logger.debug("Reseting git repository to \"origin/master\"...");

        Git git = new Git(Bot.mirrorRepo);
        try {
            git.reset().setMode(ResetCommand.ResetType.HARD).setRef("refs/remotes/origin/master").call();
        } catch (GitAPIException e) {
            logger.debug("exception: ", e);
        }
    }
    /**
     * Fetches, extracts, and then returns the OCA signatures from Oracle's website.
     */
    private static List<String> fetchOcaSignatures() throws IOException {
        List<String> signatures = new ArrayList<>();
        try {
            Document doc = Jsoup.connect("http://www.oracle.com/technetwork/community/oca-486395.html").get();
            // If the HTML of the oca-486395.html page changes, this selector will need to change. It should select
            // each <ul> that corresponds to a letter (A-W) or "XYZ" which groups the signatures alphabetically by
            // last name.
            Elements signatoryLetters = doc.select(".dataTable > tbody:nth-child(1) > tr:nth-child(1) > td:nth-child(1) > ul:nth-child(2n+5)");
            for (Element signatoryLetter : signatoryLetters) {
                Elements signatories = signatoryLetter.select("li");
                for (Element signatory : signatories) {
                    signatures.add(signatory.text());
                }
            }
        } catch (Selector.SelectorParseException e) {
            logger.error("\u2718 Could not extract list of OCA signatures.");
            logger.debug("Check the CSS selector as the HTML of the OCA page may have changed.");
            logger.debug("exception: ", e);
        }

        return signatures;
    }

    /**
     * Set the status of the "jfxmirror_bot" status check using the GitHub API for the given pull request.
     */
    private static void setPrStatus(PrStatus status, String prNum, String prShaHead, String statusUrl,
                                    String description, String tipBeforeImport) {
        if (status != PrStatus.SUCCESS && tipBeforeImport != null) {
            rollback(tipBeforeImport);
        }
        ObjectNode pendingStatus = JsonNodeFactory.instance.objectNode();
        pendingStatus.put("state", status.toString().toLowerCase(US));
        pendingStatus.put("target_url", Bot.BASE_URI.resolve("pr/" + prNum + "/" + prShaHead + "/index.html")
                .toASCIIString());
        pendingStatus.put("description", description);
        pendingStatus.put("context", BOT_USERNAME);

        Response statusResponse = Bot.httpClient.target(statusUrl)
                .request()
                .header("Authorization", "token " + GH_ACCESS_TOKEN)
                .accept(GH_ACCEPT)
                .post(Entity.json(pendingStatus.toString()));

        if (statusResponse.getStatus() == 404) {
            logger.error("GitHub API authentication failed, are you sure the \"jfxmirror_gh_token\"\n" +
                    "environment variable is set correctly?");
            Bot.cleanup();
            System.exit(1);
        }
    }

    /**
     * Converts the git patch file at the given {@code patchUrl} to a mercurial patch.
     * <p>
     * Based on https://github.com/mozilla/moz-git-tools/blob/master/git-patch-to-hg-patch
     */
    private static String convertGitPatchToHgPatch(String patchUrl) throws IOException, MessagingException {
        String gitPatch = new Scanner(new URL(patchUrl).openStream(), "UTF-8").useDelimiter("\\A").next();
        Session session = Session.getDefaultInstance(new Properties());
        MimeMessage emailMessage = new MimeMessage(session, new ByteArrayInputStream(gitPatch.getBytes(UTF_8)));
        Map<String, String> headers = enumToMap(emailMessage.getAllHeaders());
        if (!headers.containsKey("From") || !headers.containsKey("Date")) {
            throw new MessagingException("patch at url: " + patchUrl + " did not contain \"From\" and \"Date\" headers");
        }
        return "# HG changeset patch\n" +
                "# User " + headers.get("From") + "\n" +
                "# Date " + headers.get("Date") + "\n\n" +
                emailMessage.getSubject().replaceAll("^\\[PATCH( \\d+/\\d+)?\\] ", "") + "\n\n" +
                emailMessage.getContent().toString().replaceAll("--\\s?\\n[0-9\\.]+\\n$", "");
    }

    /**
     * Convenience method that makes it a bit nicer to work with email message headers.
     */
    private static Map<String, String> enumToMap(Enumeration<Header> headers) {
        HashMap<String, String> headersMap = new HashMap<>();
        while (headers.hasMoreElements()) {
            Header header = headers.nextElement();
            headersMap.put(header.getName(), header.getValue());
        }
        return headersMap;
    }

    /**
     * Removes the double quotes surrounding the given {@code string} if it is quoted.
     * <p>
     * Examples:
     * <li>{@code stripQuotes("test")} returns {@code test}
     * <li>{@code stripQuotes("\"test\"")} returns {@code test}
     * <li>{@code stripQuotes("\"\"test\"\"")} returns {@code "test"}
     *
     * @param string the {@code String} to remove quotes from
     * @return the result of removing the quotes from the given {@code String}
     */
    private static String stripQuotes(String string) {
        if (string.length() >= 2 && string.charAt(0) == '"' && string.charAt(string.length() - 1) == '"') {
            return string.substring(1, string.length() - 1);
        }
        return string;
    }

}
