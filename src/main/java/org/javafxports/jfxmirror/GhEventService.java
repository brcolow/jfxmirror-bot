package org.javafxports.jfxmirror;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.StandardOpenOption.APPEND;
import static java.util.Locale.US;
import static java.util.regex.Pattern.CASE_INSENSITIVE;
import static org.javafxports.jfxmirror.OcaStatus.FOUND_PENDING;
import static org.javafxports.jfxmirror.OcaStatus.NOT_FOUND_PENDING;
import static org.javafxports.jfxmirror.OcaStatus.SIGNED;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

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
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.transport.RefSpec;
import org.glassfish.grizzly.http.server.Request;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.jsoup.select.Selector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aragost.javahg.commands.IdentifyCommand;
import com.aragost.javahg.commands.PullCommand;
import com.aragost.javahg.commands.UpdateCommand;
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
    private static final String USER_HOME = System.getProperty("user.home");
    private static final java.nio.file.Path STATIC_BASE = Paths.get(USER_HOME, "jfxmirror");
    private static final String OS_NAME = System.getProperty("os.name").toLowerCase(US);
    private static final String GITHUB_API = "https://api.github.com";
    private static final String GH_ACCEPT = "application/vnd.github.v3+json";
    private static final String GH_ACCESS_TOKEN = System.getenv("jfxmirror_gh_token");
    private static final String OCA_SEP = "@@@";
    private static final Pattern BUG_PATTERN = Pattern.compile("JDK-\\d\\d\\d\\d\\d\\d\\d");
    private static final Pattern DOUBLE_QUOTE_PATTERN = Pattern.compile("\"([^\"]*)\"");
    private static final Pattern FIRST_COMMENT_PATTERN = Pattern.compile("Yes,? that'?s me", CASE_INSENSITIVE);
    private static final JiraRestClientFactory CLIENT_FACTORY = new AsynchronousJiraRestClientFactory();
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
    public Response handleGhEvent(ObjectNode event,
                                  @Context Request request,
                                  @Context ContainerRequestContext requestContext) {
        // TODO: Verify that the request is actually from github?
        // TODO: Currently we are using ngrok for testing, so these are not github. Inspect them for real when we use
        // a publicly visible server.
        logger.debug("Remote addr: " + request.getRemoteAddr());
        logger.debug("Remote host: " + request.getRemoteHost());
        logger.debug("Remote user: " + request.getRemoteUser());
        logger.debug("Sceme: " + request.getScheme());
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
        switch (commentEvent.get("action").asText().toLowerCase(US)) {
            case "created":
            case "edited":
                break;
            default:
                // Nothing to do.
                return Response.ok().build();
        }

        boolean commentOnPrWeCareAbout = false;
        OcaStatus ocaStatus = null;
        File[] prDirs = Paths.get(USER_HOME, "jfxmirror", "pr")
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
                            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
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
            // Not a comment directed at us.
            return Response.ok().build();
        }

        // Possible valid comments:
        // 1.) @jfxmirror_bot Yes, that's me
        // 2.) @jfxmirror_bot I have signed the OCA under the name \"name\"
        // 3.) @jfxmirror_bot I have now signed the OCA using my GitHub username
        String comment = issueBody.replaceFirst("@" + BOT_USERNAME + " ", "");

        String username = commentEvent.get("comment").get("user").get("login").asText();
        String issueUrl = commentEvent.get("issue").get("url").asText();
        String reply = "@" + username + " ";
        java.nio.file.Path ocaFile = Paths.get(USER_HOME, "jfxmirror", "oca.txt");
        java.nio.file.Path ocaMarkerFile = Paths.get(USER_HOME, "jfxmirror", "pr",
                commentEvent.get("issue").get("number").asText(), ".oca");

        Matcher firstPatternMatcher = FIRST_COMMENT_PATTERN.matcher(comment);
        if (ocaStatus == FOUND_PENDING && firstPatternMatcher.find()) {
            reply += OcaReplies.replyWhenUserConfirmsIdentity();
            try {
                Files.write(ocaMarkerFile, SIGNED.name().toLowerCase(US).getBytes(UTF_8));
                Files.write(ocaFile, (username + OCA_SEP + username + "\n").getBytes(UTF_8), APPEND);
            } catch (IOException e) {
                logger.error("\u2718 Could not update OCA status.");
                logger.debug("exception: ", e);
                return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
            }
        } else if (comment.toLowerCase(Locale.US).startsWith("i have signed the oca under the name")) {
            // Grab the name in double quotes.
            Matcher doubleQuoteMatcher = DOUBLE_QUOTE_PATTERN.matcher(comment);
            if (doubleQuoteMatcher.find()) {
                String name = stripQuotes(doubleQuoteMatcher.group(0));
                try {
                    boolean foundName = searchOcaSignaturesFor(name);
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
            } else {
                // Malformed response, did not contain a name in quotes.
                reply += OcaReplies.replyWhenNotFoundNameInQuotes(BOT_USERNAME);
            }
        } else if (comment.toLowerCase(Locale.US).startsWith("i have now signed the oca using my github username")) {
            try {
                boolean foundUsername = searchOcaSignaturesFor(username);
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
            // Can't understand the response.
            reply += OcaReplies.replyWhenCantUnderstandResponse();
        }

        try (Response commentResponse = Bot.httpClient.target(issueUrl)
                .request()
                .header("Authorization", "token " + GH_ACCESS_TOKEN)
                .accept(GH_ACCEPT)
                .post(Entity.json(JsonNodeFactory.instance.objectNode().put("body", reply)))) {
            if (commentResponse.getStatus() == 404) {
                logger.error("\u2718 Could not post comment on PR #" + commentEvent.get("issue").get("number"));
                logger.debug("GitHub response: " + commentResponse.readEntity(String.class));
                return Response.status(Response.Status.BAD_REQUEST).build();
            }
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
        logger.debug("New event: Pull request #" + prNum + " " + action + ".");
        String[] repoFullName = pullRequestEvent.get("repository").get("full_name").asText().split("/");
        String statusUrl = String.format("%s/repos/%s/%s/statuses/%s", GITHUB_API,
                repoFullName[0], repoFullName[1], prShaHead);
        PullRequestContext pullRequestContext = new PullRequestContext(pullRequest, prNum, prShaHead, statusUrl);

        // Set the status of the PR to pending while we do the necessary checks.
        setPrStatus(PrStatus.PENDING, prNum, prShaHead, statusUrl, "Checking for upstream mergeability...", null);

        // Create directory that will contain the git and hg patches.
        java.nio.file.Path patchDir = Paths.get(USER_HOME, "jfxmirror", "pr", prNum, prShaHead, "patch");
        if (!Files.exists(patchDir)) {
            try {
                Files.createDirectories(patchDir);
            } catch (IOException e) {
                return setError(pullRequestContext, tipBeforeImport, "Could not create patches directory.", e);
            }
        }

        Git git = new Git(Bot.mirrorRepo);

        String mirrorBaseBranch = "master"; // FIXME: May want to switch to "develop"
        // Update the local git repository (fetching any new changes from github remote).
        try {
            git.fetch().setRemote("origin").setRefSpecs("refs/heads/" + mirrorBaseBranch).call();
            git.rebase().setUpstream("refs/heads/" + mirrorBaseBranch).call();
        } catch (GitAPIException e) {
            return setError(pullRequestContext, tipBeforeImport, "Could not sync git mirror repository.", e);
        }

        // The git mirror repository can lag behind the upstream hg repository because it is only synced daily. So,
        // first we need to find the most recent commit from upstream that has been merged in to the mirror.
        RevCommit latestUpstreamCommit;
        try {
            latestUpstreamCommit = findLatestUpstreamCommit(git);
        }
        catch (IOException e) {
            return setError(pullRequestContext, tipBeforeImport,
                    "Could not determine latest upstream commit in mirror.", e);
        }

        // Fetch the commits array from the pull request JSON sent by GitHub.
        JsonNode commitsJson;
        try {
            commitsJson = fetchCommitsJson(pullRequest);
        }
        catch (IOException e) {
            return setError(pullRequestContext, tipBeforeImport, "Could not read commits JSON.", e);
        }

        // Construct a diff between "latestUpstreamCommit" (the most recent commit from upstream that has been
        // merged in to the mirror) and the changes introduced in the PR, producing a git formatted patch file.
        try {
            writePullRequestAsPatch(git, pullRequestContext, commitsJson, patchDir);
        } catch (IOException e) {
            if (e.getCause().getClass().equals(EmtpyCommitException.class)) {
                // If the commit is empty that means this PR only touches blacklisted files, so it has no intention
                // of being merged to upstream, so we can stop now.
                logger.debug("This PR only has changes to blacklisted files, so skipping upstream mergeability checks.");
                setPrStatus(PrStatus.SUCCESS, prNum, prShaHead, statusUrl,
                        "PR has no changes meant for upstream.", tipBeforeImport);
                return Response.ok().build();
            }

            return setError(pullRequestContext, tipBeforeImport, "Could not convert git patch to hg patch.", e);
        }

        // Convert the git formatted patch file to an hg formatted patch file.
        java.nio.file.Path hgPatchPath;
        try {
            hgPatchPath = writeGitPatchAsHgPatch(patchDir);
        } catch (IOException e) {
            return setError(pullRequestContext, tipBeforeImport, "Could not convert git patch to hg patch.", e);
        }

        // If necessary, check if user who opened the PR has signed the OCA.
        try {
            fetchOcaStatus(pullRequestContext);
        } catch (IOException e) {
            return setError(pullRequestContext, tipBeforeImport,
                    "Could not determine if user who opened PR has signed OCA.", e);
        }

        // See if there is a JBS bug associated with this PR.
        findReferencedJbsBugs(pullRequestContext, commitsJson);

        // TODO: Find "latestUpstreamCommit" in the mercurial repository and set tip to that before importing patch.
        // The rationale is so the diff generated by git format-patch will apply cleanly to hg because they are both
        // diffed against the same commit (namely: "latestUpstreamCommit").

        // Update our local upstream repository (i.e. fetch new changesets from the hg.openjdk.java.net/openjfx remote).
        try {
            // hg pull && hg update
            PullCommand.on(Bot.upstreamRepo).execute();
            UpdateCommand.on(Bot.upstreamRepo).execute();
        } catch (IOException e) {
            return setError(pullRequestContext, tipBeforeImport, "Could not update upstream hg repository.", e);
        }

        // Apply the hg patch to the tip of openjfx remote, since this whole check is about
        // "mergeability", that would ensure that conflicts don't happen when merging to upstream.
        // Apply the hg patch to our local upstream hg repo.
        try {
            List<java.nio.file.Path> originalRejects = applyHgPatch(hgPatchPath);
            if (!originalRejects.isEmpty()) {
                List<java.nio.file.Path> copiedRejects = new ArrayList<>(originalRejects.size());
                java.nio.file.Path rejectsPath = Paths.get(USER_HOME, "jfxmirror", "pr",
                        pullRequestContext.getPrNum(), pullRequestContext.getPrShaHead(), "rejects");
                if (!Files.exists(rejectsPath)) {
                    Files.createDirectories(rejectsPath);
                }
                for (java.nio.file.Path reject : originalRejects) {
                    java.nio.file.Path copiedReject = rejectsPath.resolve(reject.getFileName());
                    Files.copy(reject, copiedReject);
                    copiedRejects.add(copiedReject);
                }
                pullRequestContext.setRejects(copiedRejects);
                pullRequestContext.setPrStatus(PrStatus.FAILURE);
                StatusPage.createStatusPageHtml(pullRequestContext);
                setPrStatus(PrStatus.FAILURE, pullRequestContext.getPrNum(), pullRequestContext.getPrShaHead(),
                        pullRequestContext.getStatusUrl(), "Could not merge PR into upstream.", tipBeforeImport);
                return Response.ok().build();
            }
        } catch (IOException e) {
            return setError(pullRequestContext, tipBeforeImport,
                    "Could not apply PR changes to upstream hg repository.", e);
        }

        // hg identify --rev -2
        String previousCommit = IdentifyCommand.on(Bot.upstreamRepo).id().rev("-2").execute();
        // TODO: In what cases does this fail?
        if (!previousCommit.equals(tipBeforeImport)) {
            logger.error("\u2718 The tip before importing is not equal to the previous commit!");
            setPrStatus(PrStatus.ERROR, prNum, prShaHead, statusUrl, "Upstream hg repository error.", null);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
        }

        // Run jcheck against the PR's changes (http://openjdk.java.net/projects/code-tools/jcheck/).
        try {
            runJCheck(pullRequestContext);
        } catch (IOException e) {
            return setError(pullRequestContext, tipBeforeImport, "Could not run jcheck.", e);
        }

        // Generate a webrev with the PR's changes.
        try {
            generateWebRev(pullRequestContext, previousCommit);
        } catch (IOException e) {
            return setError(pullRequestContext, tipBeforeImport, "Could not generate webrev for PR.", e);
        }

        // If we get this far, then we can set PR status to success.
        pullRequestContext.setPrStatus(PrStatus.SUCCESS);
        setPrStatus(PrStatus.SUCCESS, prNum, prShaHead, statusUrl, "Ready to merge with upstream.", tipBeforeImport);

        // Create the status page "pr/{prNum}/{prShaHead}/index.html" from the above data (that is linked to by
        // the jfxmirror_bot PR status check).
        try {
            StatusPage.createStatusPageHtml(pullRequestContext);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        // Rollback upstream hg repository to "tipBeforeImport".
        // TODO: Instead of doing this, we could create a temporary branch to work on before importing the GH PR.
        rollback(tipBeforeImport);

        return Response.ok().build();
    }

    private static List<java.nio.file.Path> applyHgPatch(java.nio.file.Path hgPatchPath) throws IOException {
        ProcessBuilder importBuilder = new ProcessBuilder("hg", "import", hgPatchPath.toString(), "--bypass")
                .redirectErrorStream(true)
                .directory(Bot.upstreamRepo.getDirectory());
        Process hgImport = importBuilder.start();
        String hgOut = new String(hgImport.getInputStream().readAllBytes(), UTF_8);
        final List<java.nio.file.Path> rejects = new ArrayList<>();
        if (hgOut.contains("abort: patch failed to apply")) {
            logger.debug("Mercurial patch did not apply cleanly, searching for rejects...");
            rejects.addAll(Files.find(Bot.upstreamRepo.getDirectory().toPath(), 30, (p, bfa) ->
                    bfa.isRegularFile() && p.toString().endsWith(".rej")).collect(Collectors.toList());
        }
        try {
            boolean finished = hgImport.waitFor(1, TimeUnit.MINUTES);
            if (!finished) {
                hgImport.destroyForcibly();
                throw new IOException("could not apply hg patch in 1 minute");
            }
        } catch (InterruptedException e) {
            hgImport.destroyForcibly();
            throw new IOException(e);
        }
        hgImport.destroy();
        return rejects;
    }

    private static Response setError(PullRequestContext pullRequestContext, String tipBeforeImport,
                                     String errorMessage, Exception exception) {
        Objects.requireNonNull(pullRequestContext, "pullRequestContext must not be null");
        Objects.requireNonNull(tipBeforeImport, "tipBeforeImport must not be null");
        Objects.requireNonNull(errorMessage, "errorMessage must not be null");
        Objects.requireNonNull(exception, "exception must not be null");

        setPrStatus(PrStatus.ERROR, pullRequestContext.getPrNum(), pullRequestContext.getPrShaHead(),
                pullRequestContext.getStatusUrl(), errorMessage, tipBeforeImport);
        logger.error("\u2718 " + errorMessage);
        logger.debug("exception: ", exception);
        return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
    }

    /**
     * Checks to see if any JBS bugs are associated with the given pull request and updates the
     * {@code pullRequestContext} accordingly. JBS bugs are determined to be associated with a pull
     * request if any of the following places contain the text "JDK-xxxxxxx" where xxxxxxx is some number:
     * <ol>
     * <li> Each commit message of the commits that make up this PR.
     * <li> The PR title.
     * <li> The branch name of this PR.
     * </ol>
     */
    private static void findReferencedJbsBugs(PullRequestContext pullRequestContext, JsonNode commitsJson) {
        Objects.requireNonNull(pullRequestContext, "pullRequestContext must not be null");
        Objects.requireNonNull(commitsJson, "commitsJson must not be null");

        logger.debug("Checking if this PR is associated with any JBS bugs...");
        Set<String> jbsBugsReferenced = new HashSet<>();

        // Check if any commit messages of this PR contain a JBS bug (like JDK-xxxxxxx).
        for (JsonNode commitJson : commitsJson) {
            String commitMessage = commitJson.get("commit").get("message").asText();
            Matcher bugPatternMatcher = BUG_PATTERN.matcher(commitMessage);
            if (bugPatternMatcher.find()) {
                jbsBugsReferenced.add(bugPatternMatcher.group(0));
            }
        }

        // Check if the branch name of the PR contains a JBS bug.
        String prBranchName = pullRequestContext.getPullRequest().get("head").get("ref").asText();
        Matcher bugMatcher = BUG_PATTERN.matcher(prBranchName);
        if (bugMatcher.find()) {
            jbsBugsReferenced.add(bugMatcher.group(0));
        }

        // Check if the PR title contains a JBS bug.
        String prTitle = pullRequestContext.getPullRequest().get("title").asText();
        bugMatcher = BUG_PATTERN.matcher(prTitle);
        if (bugMatcher.find()) {
            jbsBugsReferenced.add(bugMatcher.group(0));
        }

        Set<String> jbsBugsReferencedAndFound = new HashSet<>();
        try (JiraRestClient jiraRestClient = CLIENT_FACTORY.create(URI.create("https://bugs.openjdk.java.net"),
                new AnonymousAuthenticationHandler())) {
            for (String jbsBug : jbsBugsReferenced) {
                Promise<SearchResult> searchJqlPromise = jiraRestClient.getSearchClient().searchJql(
                        "project = JDK AND status IN ('Open', 'In Progress', 'New', 'Provisional') " +
                                "AND component = javafx AND id = " + jbsBug);
                Set<Issue> issues = Sets.newHashSet(searchJqlPromise.claim().getIssues());
                if (!issues.isEmpty()) {
                    jbsBugsReferencedAndFound.add(jbsBug);
                }
            }
            pullRequestContext.setJbsBugsReferenced(jbsBugsReferenced);
            pullRequestContext.setJbsBugsReferencedButNotFound(Sets.difference(
                    jbsBugsReferenced, jbsBugsReferencedAndFound));
        } catch (IOException e) {
            logger.debug("exception: ", e);
            throw new RuntimeException(e);
        }
    }

    private static void fetchOcaStatus(PullRequestContext pullRequestContext) throws IOException {
        Objects.requireNonNull(pullRequestContext, "pullRequestContext must not be null");

        String username = pullRequestContext.getPullRequest().get("user").get("login").asText();
        java.nio.file.Path ocaMarkerFile = Paths.get(USER_HOME, "jfxmirror", "pr",
                pullRequestContext.getPrNum(), ".oca");
        OcaStatus ocaStatus = NOT_FOUND_PENDING;
        if (Files.exists(ocaMarkerFile)) {
            String ocaMarkerContents = new String(Files.readAllBytes(ocaMarkerFile), UTF_8);
            ocaStatus = OcaStatus.valueOf(ocaMarkerContents.toUpperCase(Locale.US));
            logger.debug("Already checked if \"" + username + "\" has signed the OCA.");
        } else {
            logger.debug("Checking if \"" + username + "\" has signed the OCA...");
            java.nio.file.Path ocaFile = Paths.get(USER_HOME, "jfxmirror", "oca.txt");
            String ocaName;
            List<String> ocaFileLines = Files.readAllLines(ocaFile, UTF_8);
            for (String line : ocaFileLines) {
                String[] gitHubUsernameOcaName = line.split(OCA_SEP);
                if (gitHubUsernameOcaName.length != 2) {
                    throw new IOException("OCA signature file malformed (expecting separator \"" + OCA_SEP + "\")");
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
            if (ocaStatus != SIGNED) {
                // We have no record that the user who opened this PR signed the OCA so let's check Oracle's OCA
                // page to see if we can find their username.
                boolean foundUsername = false;
                String ocaLine = null;
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

                String commentsUrl = pullRequestContext.getPullRequest().get("_links").get("comments")
                        .get("href").asText();
                String comment = "@" + username + " ";
                if (foundUsername) {
                    Files.write(ocaMarkerFile, FOUND_PENDING.name().toLowerCase(US).getBytes(UTF_8));
                    ocaStatus = FOUND_PENDING;
                    logger.debug("Found GitHub username of user who opened PR on OCA signature list.");
                    comment += OcaComments.commentWhenFoundUsername(ocaLine, BOT_USERNAME);
                } else {
                    Files.write(ocaMarkerFile, NOT_FOUND_PENDING.name().toLowerCase(US).getBytes(UTF_8));
                    ocaStatus = NOT_FOUND_PENDING;
                    // Post comment on PR telling them we could not find their github username listed on OCA signature
                    // page, ask them if they have signed it.
                    comment += OcaComments.commentWhenNotFoundUsername();
                }
                comment += OcaComments.defaultComment(BOT_USERNAME);

                try (Response commentResponse = Bot.httpClient.target(commentsUrl)
                        .request()
                        .header("Authorization", "token " + GH_ACCESS_TOKEN)
                        .accept(GH_ACCEPT)
                        .post(Entity.json(JsonNodeFactory.instance.objectNode().put("body", comment).toString()))) {
                    if (commentResponse.getStatus() == 404) {
                        throw new IOException("404 from github, trying to post comment on PR: " +
                                commentResponse.readEntity(String.class));
                    }
                }
            }
        }
        pullRequestContext.setOcaStatus(ocaStatus);
    }

    private static void generateWebRev(PullRequestContext pullRequestContext, String previousCommit) throws IOException {
        Objects.requireNonNull(pullRequestContext, "pullRequestContext must not be null");
        Objects.requireNonNull(previousCommit, "previousCommit must not be null");

        java.nio.file.Path webRevOutputPath = Paths.get(USER_HOME,
                "jfxmirror", "pr", pullRequestContext.getPrNum(), pullRequestContext.getPrShaHead());
        String[] webrevBugArgs = { "", "" };
        if (!pullRequestContext.getJbsBugsReferenced().isEmpty()) {
            webrevBugArgs[0] = "-c";
            webrevBugArgs[1] = pullRequestContext.getJbsBugsReferenced().iterator().next().substring(4);
        }
        ProcessBuilder webrevBuilder;
        if (OS_NAME.contains("windows")) {
            // Calling ksh to generate webrev requires having bash in the Windows %PATH%, this works on e.g. WSL.
            String kshInvocation = "\"ksh " +
                    Paths.get(USER_HOME, "jfxmirror", "webrev", "webrev.ksh").toString()
                            .replaceFirst("C:\\\\", "/mnt/c/").replaceAll("\\\\", "/") +
                    " -r " + previousCommit + " -N -m -o " + webRevOutputPath.toString()
                    .replaceFirst("C:\\\\", "/mnt/c/").replaceAll("\\\\", "/") +
                    (!pullRequestContext.getJbsBugsReferenced().isEmpty() ?
                            (" " + webrevBugArgs[0] + " " + webrevBugArgs[1]) : "") + "\"";
            webrevBuilder = new ProcessBuilder("bash", "-c", kshInvocation);
        } else {
            // Just call ksh directly.
            webrevBuilder = new ProcessBuilder("ksh",
                    Paths.get(USER_HOME, "jfxmirror", "webrev", "webrev.ksh").toString(),
                    "-N", "-m", webrevBugArgs[0], webrevBugArgs[1],
                    "-o", webRevOutputPath.toString());
        }
        webrevBuilder.directory(Bot.upstreamRepo.getDirectory());
        Process webrev = null;
        try {
            webrevBuilder.inheritIO();
            logger.debug("Generating webrev for PR #" + pullRequestContext.getPrNum() +
                    " (" + pullRequestContext.getPrShaHead() + ")...");
            webrev = webrevBuilder.start();
            boolean finished = webrev.waitFor(2, TimeUnit.MINUTES);
            if (!finished) {
                webrev.destroyForcibly();
                throw new IOException("could not generate webrev in 2 minutes");
            }
        } catch (SecurityException | InterruptedException e) {
            if (webrev != null) {
                webrev.destroyForcibly();
            }
            throw new IOException(e);
        }
        webrev.destroy();
    }

    private static void runJCheck(PullRequestContext pullRequestContext) throws IOException {
        Objects.requireNonNull(pullRequestContext, "pullRequestContext must not be null");

        logger.debug("Running jcheck on PR #" + pullRequestContext.getPrNum() +
                " (" + pullRequestContext.getPrShaHead() + ")...");
        java.nio.file.Path jcheckOutputPath = Paths.get(USER_HOME, "jfxmirror", "pr",
                pullRequestContext.getPrNum(), pullRequestContext.getPrShaHead(), "jcheck.txt");
        Files.write(jcheckOutputPath, "".getBytes(), StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        ProcessBuilder jcheckBuilder = new ProcessBuilder("hg", "jcheck")
                .directory(Bot.upstreamRepo.getDirectory())
                .redirectError(jcheckOutputPath.toFile())
                .redirectOutput(jcheckOutputPath.toFile());
        Process jcheck = jcheckBuilder.start();
        try {
            boolean finished = jcheck.waitFor(1, TimeUnit.MINUTES);
            if (!finished) {
                jcheck.destroyForcibly();
                throw new IOException("could not run jcheck in 1 minute");
            }
        } catch (InterruptedException e) {
            jcheck.destroyForcibly();
            throw new IOException(e);
        }
        jcheck.destroy();
    }

    private static void writePullRequestAsPatch(Git git, PullRequestContext pullRequestContext,
                                                JsonNode commitsJson, java.nio.file.Path patchDir) throws IOException {
        Objects.requireNonNull(git, "git must not be null");
        Objects.requireNonNull(pullRequestContext, "pullRequestContext must not be null");
        Objects.requireNonNull(commitsJson, "commitsJson must not be null");
        Objects.requireNonNull(patchDir, "patchDir must not be null");

        StringBuilder commitMessagesConcat = new StringBuilder();
        for (JsonNode commitJson : commitsJson) {
            commitMessagesConcat.append(commitJson.get("commit").get("message").asText());
        }

        Process gitProcess = null;
        try {
            // Fetch and checkout pull request.
            git.fetch().setRemote("origin").setRefSpecs(new RefSpec(
                    "refs/pull/" + pullRequestContext.getPrNum() + "/head:refs/heads/" + "pr-" +
                            pullRequestContext.getPrShaHead())).call();
            git.checkout().setName("pr-" + pullRequestContext.getPrShaHead()).call();
            RevCommit latestCommitOfPr = git.log().setMaxCount(1).call().iterator().next();

            // Squash all commits in the PR to one (concatenate commit messages).
            git.reset().setMode(ResetCommand.ResetType.SOFT).setRef(Bot.mirrorRepo.resolve(
                    "HEAD^" + commitsJson.size()).getName()).call();
            // Remove any "blacklisted" files (files that are specific to the mirror git repository (such as CI infrastructure,
            // GitHub contributing/README files, etc.) From what we could determine, jgit does not support globs
            // (e.g. ".ci/**"), so every file must be listed individually.
            git.reset().setRef(Constants.HEAD).addPath(".travis.yml").addPath("appveyor.yml")
                    .addPath(".github/README.md").addPath(".github/CONTRIBUTING.md").addPath(".ci/before_install.sh")
                    .addPath(".ci/script.sh").call();
            RevCommit squashedCommit = git.commit().setMessage(commitMessagesConcat.toString())
                        .setAuthor(latestCommitOfPr.getAuthorIdent())
                        .setCommitter(latestCommitOfPr.getCommitterIdent())
                        .setAllowEmpty(false).call();
            // Now the PR is made up of one squashed commit and blacklisted files are removed. So we use
            // "git format-patch" to convert the squashed commit into a single patch file. We use the git process
            // here because jgit does not make it easy to write a patch file.
            ProcessBuilder gitProcessBuilder = new ProcessBuilder("git", "format-patch", "-1", squashedCommit.getName(),
                    "--stdout", "--minimal")
                    .directory(Bot.mirrorRepo.getDirectory().toPath().getParent().toFile())
                    .redirectError(patchDir.resolve("git.patch").toFile())
                    .redirectOutput(patchDir.resolve("git.patch").toFile());
            gitProcess = gitProcessBuilder.start();
            boolean finished = gitProcess.waitFor(30, TimeUnit.SECONDS);
            if (!finished) {
                gitProcess.destroyForcibly();
                throw new IOException("could not run \"git format-patch\" in 30 seconds");
            }
            gitProcess.destroy();
        } catch (GitAPIException | InterruptedException e) {
            if (gitProcess != null) {
                gitProcess.destroyForcibly();
            }
            throw new IOException(e);
        }
    }

    private static JsonNode fetchCommitsJson(JsonNode pullRequest) throws IOException {
        Objects.requireNonNull(pullRequest, "pullRequest must not be null");

        String commitsUrl = pullRequest.get("_links").get("commits").get("href").asText();
        try (Response commitsResponse = Bot.httpClient.target(commitsUrl + "?per_page=250")
                .request()
                .header("Authorization", "token " + GH_ACCESS_TOKEN)
                .accept(GH_ACCEPT)
                .get()) {
            return new ObjectMapper().readTree(commitsResponse.readEntity(String.class));
        }
    }

    private static RevCommit findLatestUpstreamCommit(Git git) throws IOException {
        Objects.requireNonNull(git, "git must not be null");

        RevCommit mostRecentUpstreamCommit = null;
        RevCommit latestMergeCommit = null;

        Iterable<RevCommit> commits;
        try {
            commits = git.log().all().call();
        } catch (GitAPIException e) {
            throw new IOException(e);
        }

        for (RevCommit commit : commits) {
            if (commit.getAuthorIdent().getName().equalsIgnoreCase("javafxports-github-bot") &&
                    commit.getShortMessage().equalsIgnoreCase("Merge from (root)")) {
                latestMergeCommit = commit;
                break;
            }
        }

        if (latestMergeCommit == null) {
            throw new IOException("could not find commit with author: \"javafxports-github-bot\" " +
                    "and message: \"Merge from (root)\"");
        }

        for (RevCommit commit : latestMergeCommit.getParents()) {
            if (!commit.getAuthorIdent().getName().equalsIgnoreCase("javafxports-github-bot") &&
                    !commit.getShortMessage().contains("Merge from (root)")) {
                logger.info("\u2713 Found latest merge commit by " + commit.getAuthorIdent().getName() +
                        ": \"" + commit.getShortMessage() + "\" (" + commit.getName() + ")");
                mostRecentUpstreamCommit = commit;
                break;
            }
        }

        if (mostRecentUpstreamCommit == null) {
            throw new IOException("could not find commit with author NOT equal to: \"javafxports-github-bot\" " +
                    "and message NOT equal to: \"Merge from (root)\" in parents of latest merge commit");
        }

        return mostRecentUpstreamCommit;
    }

    /**
     * Rollback the upstream mercurial repository iff the given {@code tipToRollbackTo} is the previous tip
     * before importing.
     */
    private static void rollback(String tipToRollbackTo) {
        Objects.requireNonNull(tipToRollbackTo, "tipToRollbackTo must not be null");

        // hg identify --rev -2
        String tipMinusOne = IdentifyCommand.on(Bot.upstreamRepo).id().rev("-2").execute();
        if (tipMinusOne.equals(tipToRollbackTo)) {
            logger.debug("Rolling mercurial back to rev before patch import...");
            try {
                // hg strip --rev -1 --no-backup
                StripCommand.on(Bot.upstreamRepo).rev("-1").noBackup().execute();
            } catch (Exception e) {
                logger.debug("exception: ", e);
                throw new RuntimeException(e);
            }
        }
    }

    private static void resetGitRepo() {
        logger.debug("Reseting git repository to \"origin/master\"...");
        try (Git git = new Git(Bot.mirrorRepo)) {
            git.reset().setMode(ResetCommand.ResetType.HARD).setRef("refs/remotes/origin/master").call();
        } catch (GitAPIException e) {
            logger.debug("exception: ", e);
            throw new RuntimeException(e);
        }
    }

    private boolean searchOcaSignaturesFor(String query) throws IOException {
        List<String> ocaSignatures = fetchOcaSignatures();
        for (String ocaSignature : ocaSignatures) {
            for (String split : ocaSignature.split("-")) {
                if (split.trim().equalsIgnoreCase(query)) {
                    return true;
                }
            }
        }
        return false;
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
            throw new IOException(e);
        }

        return signatures;
    }

    /**
     * Set the status of the "jfxmirror_bot" status check using the GitHub API for the given pull request.
     */
    private static void setPrStatus(PrStatus status, String prNum, String prShaHead, String statusUrl,
                                    String description, String tipBeforeImport) {
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(prNum, "prNum must not be null");
        Objects.requireNonNull(prShaHead, "prShaHead must not be null");
        Objects.requireNonNull(statusUrl, "statusUrl must not be null");
        Objects.requireNonNull(description, "description must not be null");

        if (status != PrStatus.SUCCESS && tipBeforeImport != null) {
            rollback(tipBeforeImport);
        }
        ObjectNode pendingStatus = JsonNodeFactory.instance.objectNode();
        pendingStatus.put("state", status.toString().toLowerCase(US));
        pendingStatus.put("target_url", Bot.baseUri.resolve("pr/" + prNum + "/" + prShaHead + "/index.html")
                .toASCIIString());
        pendingStatus.put("description", description);
        pendingStatus.put("context", BOT_USERNAME);

        try (Response statusResponse = Bot.httpClient.target(statusUrl)
                .request()
                .header("Authorization", "token " + GH_ACCESS_TOKEN)
                .accept(GH_ACCEPT)
                .post(Entity.json(pendingStatus.toString()))) {
            if (statusResponse.getStatus() == 404) {
                logger.error("GitHub API authentication failed, are you sure the \"jfxmirror_gh_token\"\n" +
                        "environment variable is set correctly?");
                Bot.cleanup();
                System.exit(1);
            }
        }
    }

    /**
     * Converts the git patch file "git.patch" contained in the given {@code patchDir} to a mercurial
     * patch and writes it to "hg.patch" inside of the given {@code patchDir}.
     * <p>
     * Based on https://github.com/mozilla/moz-git-tools/blob/master/git-patch-to-hg-patch
     */
    private static java.nio.file.Path writeGitPatchAsHgPatch(java.nio.file.Path patchDir) throws IOException {
        Objects.requireNonNull(patchDir, "patchDir must not be null");

        if (!patchDir.resolve("git.patch").toFile().exists()) {
            throw new RuntimeException("patchDir did not contain git.patch");
        }
        String gitPatch = new String(Files.readAllBytes(patchDir.resolve("git.patch")), UTF_8);
        Session session = Session.getDefaultInstance(new Properties());
        MimeMessage emailMessage;
        try {
            emailMessage = new MimeMessage(session, new ByteArrayInputStream(gitPatch.getBytes(UTF_8)));
            Map<String, String> headers = enumToMap(emailMessage.getAllHeaders());
            if (!headers.containsKey("From") || !headers.containsKey("Date")) {
                throw new IOException("patch (" + patchDir.resolve("git.patch").toString() +
                        ") did not contain \"From\" and \"Date\" headers");
            }
            String hgPatch = "# HG changeset patch\n" +
                    "# User " + headers.get("From") + "\n" +
                    "# Date " + headers.get("Date") + "\n\n" +
                    emailMessage.getSubject().replaceAll("^\\[PATCH( \\d+/\\d+)?\\] ", "") + "\n\n" +
                    emailMessage.getContent().toString().replaceAll("--\\s?\\n[0-9\\.]+\\n$", "");
            Files.write(patchDir.resolve("hg.patch"), hgPatch.getBytes(UTF_8));
            return patchDir.resolve("hg.patch");
        } catch (MessagingException e) {
            throw new IOException(e);
        }
    }

    /**
     * Convenience method that makes it a bit nicer to work with email message headers.
     */
    private static Map<String, String> enumToMap(Enumeration<Header> headers) {
        Objects.requireNonNull(headers, "headers must not be null");

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
        Objects.requireNonNull(string, "string must not be null");

        if (string.length() >= 2 && string.charAt(0) == '"' && string.charAt(string.length() - 1) == '"') {
            return string.substring(1, string.length() - 1);
        }
        return string;
    }

}
