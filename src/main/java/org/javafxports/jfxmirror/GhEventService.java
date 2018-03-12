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
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Scanner;
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

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.jsoup.select.Selector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aragost.javahg.Changeset;
import com.aragost.javahg.commands.ExecutionException;
import com.aragost.javahg.commands.IdentifyCommand;
import com.aragost.javahg.commands.ImportCommand;
import com.aragost.javahg.commands.UpdateCommand;
import com.aragost.javahg.commands.UpdateResult;
import com.aragost.javahg.ext.mq.StripCommand;
import com.aragost.javahg.internals.GenericCommand;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

@Path("/")
public class GhEventService {

    private static final String BOT_USERNAME = "jfxmirror-bot";
    private static final java.nio.file.Path STATIC_BASE = Paths.get(System.getProperty("user.home"), "jfxmirror");
    private static final String OS_NAME = System.getProperty("os.name").toLowerCase(US);
    private static final String GITHUB_API = "https://api.github.com";
    private static final String GH_ACCEPT = "application/vnd.github.v3+json";
    private static final String GH_ACCESS_TOKEN = System.getenv("jfxmirror_gh_token");
    private static final String OCA_SEP = "@@@";
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
        if (ext.equalsIgnoreCase("patch")) {
            contentType = "text/plain";
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
        return Response.ok(STATIC_BASE.resolve("pr").resolve(path).resolve("index.html").toFile()).build();
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
                return handlePullRequest(event);
            default:
                logger.debug("Got POST to /pr but \"X-GitHub-Event\" header was not one of \"ping\", \"pull_request\", " +
                        "\"issue_comment\" but was: " + gitHubEvent);
                logger.debug("Make sure that the only checked trigger events for the jfxmirror_bot webhook are \"Pull request\" and \"Issue comment\"");
                return Response.status(Response.Status.BAD_REQUEST).entity(new ObjectNode(JsonNodeFactory.instance)
                        .put("error", "\"X-GitHub-Event\" header was not one of \"ping\", \"pull_request\", \"issue_comment\" but was: " + gitHubEvent))
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
        File[] prDirs = Paths.get(System.getProperty("user.home"), "jfxmirror", "pr").toFile().listFiles(File::isDirectory);
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
            reply += "Okay, thanks. We won't ask again.";
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
            Pattern pattern = Pattern.compile("\"([^\"]*)\"");
            Matcher matcher = pattern.matcher(comment);
            String name = null;
            if (matcher.find()) {
                name = stripQuotes(matcher.group(0));
            } else {
                // Malformed response, did not contain a name in quotes.
                reply += "Sorry, we could not understand your response because we could not find a name in double quotes." +
                        "Please try again by adding a comment to this PR of the form: " +
                        "`@" + BOT_USERNAME + " I have signed the OCA under the name {name}`.";
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
                    reply += "Okay, thanks :thumbsup:. We have updated our records that you have signed the OCA " +
                            "under the name `" + name + "`.";
                    Files.write(ocaMarkerFile, SIGNED.name().toLowerCase(US).getBytes(UTF_8));
                    Files.write(ocaFile, (username + OCA_SEP + name + "\n").getBytes(UTF_8), APPEND);
                } else {
                    reply += "You said that you signed the OCA under your name `" + name + "`, but we weren't " +
                            "able to find that name on the " +
                            "[OCA signatures page](http://www.oracle.com/technetwork/community/oca-486395.html) :flushed:." +
                            "Make sure it is correct and try again with the correct name by adding a comment to this " +
                            "PR of the form: `@" + BOT_USERNAME + " I have signed the OCA under the name {name}`.";
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
                    reply += "Okay, thanks :thumbsup:. We have updated our records that you have signed the OCA " +
                            "using your GitHub username.";
                    Files.write(ocaMarkerFile, SIGNED.name().toLowerCase(US).getBytes(UTF_8));
                    Files.write(ocaFile, (username + OCA_SEP + username + "\n").getBytes(UTF_8), APPEND);
                } else {
                    reply += "You said that you signed the OCA under your GitHub username `" + username + "`, but we weren't " +
                            "able to find that username on the " +
                            "[OCA signatures page](http://www.oracle.com/technetwork/community/oca-486395.html) :flushed:." +
                            "Make sure it is correct and try again with the correct name by adding a comment to this " +
                            "PR of the form: `@" + BOT_USERNAME + " I have now signed the OCA using my GitHub username`.";
                }
            } catch (IOException e) {
                logger.error("\u2718 Could not download OCA signatures page.");
                logger.debug("exception: ", e);
                return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
            }
        } else {
            // Can't understand the response
            reply += "Sorry, we could not understand your response :confused:.";
        }

        Response commentResponse = Bot.httpClient.target(issueUrl)
                .request()
                .header("Authorization", "token " + GH_ACCESS_TOKEN)
                .accept(GH_ACCEPT)
                .post(Entity.json(JsonNodeFactory.instance.objectNode().put("body", reply)));

        if (commentResponse.getStatus() == 404) {
            logger.error("\u2718 Could not post comment on PR #" + commentEvent.get("issue").get("number"));
            logger.debug("GitHub response: " + commentResponse.getEntity());
            return Response.status(Response.Status.BAD_REQUEST).build();
        }
        return Response.ok().build();
    }

    /**
     * https://developer.github.com/v3/activity/events/types/#pullrequestevent
     */
    private Response handlePullRequest(ObjectNode pullRequestEvent) {
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
        String statusUrl = String.format("%s/repos/%s/%s/statuses/%s", GITHUB_API, repoFullName[0], repoFullName[1], prShaHead);

        // Set the status of the PR to pending while we do the necessary checks.
        setPrStatus(PrStatus.PENDING, prNum, prShaHead, statusUrl, "Checking for upstream mergeability...");

        // TODO: Before converting the PR patch, should it be squashed to one commit of concatenated commit messages?
        // Currently we lose the commit messages of all but the first commit.
        String patchUrl = pullRequest.get("patch_url").asText();
        String hgPatch;
        try {
            hgPatch = convertGitPatchToHgPatch(patchUrl);
        } catch (IOException | MessagingException e) {
            setPrStatus(PrStatus.ERROR, prNum, prShaHead, statusUrl, "Could not convert git patch to hg patch.");
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
                setPrStatus(PrStatus.ERROR, prNum, prShaHead, statusUrl, "Could not create patches directory.");
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
            setPrStatus(PrStatus.ERROR, prNum, prShaHead, statusUrl, "Could not write hg patch to file.");
            logger.error("\u2718 Could not write hg patch to file.");
            logger.debug("exception: ", e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
        }

        // Apply the hg patch to the upstream hg repo
        logger.debug("Fetching tip revision before import...");
        String tipBeforeImport = IdentifyCommand.on(Bot.upstreamRepo).id().rev("-1").execute();
        logger.debug("Tip revision before import: " + tipBeforeImport);
        try {
            ImportCommand importCommand = ImportCommand.on(Bot.upstreamRepo);
            // importCommand.cmdAppend("--no-commit");
            importCommand.execute(hgPatchPath.toFile());
            logger.debug("Updating upstream hg repository...");
            UpdateResult updateResult = UpdateCommand.on(Bot.upstreamRepo).execute();
        } catch (IOException | ExecutionException e) {
            setPrStatus(PrStatus.FAILURE, prNum, prShaHead, statusUrl, "Could not apply PR changeset to upstream hg repository.");
            logger.error("\u2718 Could not apply PR changeset to upstream mercurial repository.");
            logger.debug("exception: ", e);
            // FIXME: Uncomment this after testing
            // return Response.status(Response.Status.BAD_REQUEST).build();
        }
        String previousCommit = IdentifyCommand.on(Bot.upstreamRepo).id().rev("-2").execute();
        logger.debug("Previous commit (after import): " + previousCommit);
        if (!previousCommit.equals(tipBeforeImport)) {
            logger.error("\u2718 The tip before importing is not equal to the previous commit!");
            // setPrStatus(PrStatus.FAILURE, prNum, prShaHead, statusUrl, "Upstream hg repository error.");
            // Response.status(Response.Status.BAD_REQUEST).build();
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
                setPrStatus(PrStatus.ERROR, prNum, prShaHead, statusUrl, "Could not read OCA marker file.");
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
                        setPrStatus(PrStatus.ERROR, prNum, prShaHead, statusUrl, "OCA signature file malformed.");
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
                setPrStatus(PrStatus.ERROR, prNum, prShaHead, statusUrl, "Could not read OCA signatures file.");
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
                    setPrStatus(PrStatus.ERROR, prNum, prShaHead, statusUrl, "Could not download OCA signatures page.");
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
                    comment += "We attempted to determine if you have signed the Oracle Contributor Agreement (OCA) and found " +
                            "a signatory with your GitHub username: `" + ocaLine + "`. **If that's you**, add a comment on " +
                            "this PR saying: `@" + BOT_USERNAME + " Yes, that's me`. **Otherwise, if that's not you**:\n\n";
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
                    comment += "We attempted to determine if you have signed the Oracle Contributor Agreement (OCA) but could " +
                            "not find a signature line with your GitHub username.\n\n";
                }
                comment += "**If you have already signed the OCA**:\n" +
                        "Add a comment on this PR saying: `@" + BOT_USERNAME + " I have signed the OCA under the name {name}` " +
                        "where `{name}` is the first, name-like part of an OCA signature line. For example, the signature line " +
                        "`Michael Ennen - GlassFish Jersey - brcolow` has a first, name-like part of `Michael Ennen`.\n\n" +
                        "**If you have never signed the OCA before:**\n" +
                        "Follow the instructions at http://www.oracle.com/technetwork/community/oca-486395.html for " +
                        "doing so. Make sure to fill out the username portion of the form with your GitHub username. " +
                        "Once you have signed the OCA and your name has been added to the list of signatures, " +
                        "add a comment on this PR saying: `@" + BOT_USERNAME + " I have now signed the OCA using my GitHub username`.";

                Response commentResponse = Bot.httpClient.target(commentsUrl)
                        .request()
                        .header("Authorization", "token " + GH_ACCESS_TOKEN)
                        .accept(GH_ACCEPT)
                        .post(Entity.json(JsonNodeFactory.instance.objectNode().put("body", comment).toString()));

                if (commentResponse.getStatus() == 404) {
                    logger.error("\u2718 Could not post comment on PR #" + prNum + " for assisting the user who opened the " +
                            "PR with confirming their signing of the OCA.");
                    logger.debug("GitHub response: " + commentResponse.getEntity());
                    setPrStatus(PrStatus.ERROR, prNum, prShaHead, statusUrl, "Could not post comment to PR.");
                    return Response.status(Response.Status.BAD_REQUEST).build();
                }
            }
        }

        // See if there is a JBS bug associated with this PR (how? commit message? rely on jcheck?)
        // http://openjdk.java.net/guide/producingChangeset.html#changesetComment states that the "changeset message"
        // should be of the form "<bugid>: <synopsis-of-symptom>" so we could grab it from that

        // Run jcheck http://openjdk.java.net/projects/code-tools/jcheck/
        logger.debug("Running jcheck on PR #" + prNum + " (" + prShaHead + ")...");
        java.nio.file.Path jcheckOutputPath = Paths.get(System.getProperty("user.home"), "pr", prNum, prShaHead, "jcheck.txt");
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
            setPrStatus(PrStatus.ERROR, prNum, prShaHead, statusUrl, "Could not run jcheck.");
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
        }

        /*
        // This runs jcheck by using a javahg mercurial extension. The problem is that the output is not captured
        // via getErrorString(), so it is useless for our purposes. It would be much nicer to use than a raw hg process.
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
                setPrStatus(PrStatus.ERROR, prNum, prShaHead, statusUrl, "Could not generate webrev in time.");
                logger.error("\u2718 Could not generate webrev in time.");
                return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
            }
        } catch (SecurityException | IOException | InterruptedException e) {
            setPrStatus(PrStatus.ERROR, prNum, prShaHead, statusUrl, "Could not generate webrev for PR.");
            logger.error("\u2718 Encountered error trying to generate webrev.");
            logger.debug("exception: ", e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
        }

        // Make status page at "pr/{prNum}/{prShaHead}" from the above data, set status to success/fail
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
        String statusPage = StatusPage.getStatusPageHtml(prNum, prShaHead, ocaStatus);
        try {
            Files.write(statusPath.resolve("index.html"), statusPage.getBytes(UTF_8));
        } catch (IOException e) {
            logger.error("\u2718 Could not write \"index.html\" to: " + statusPath);
            logger.debug("exception: ", e);
            setPrStatus(PrStatus.ERROR, prNum, prShaHead, statusUrl, "Could not write status page.");
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
        }

        // Rollback upstream hg repository back to tipBeforeImport
        // UpdateCommand.on(Bot.upstreamRepo).rev(tipBeforeImport).clean().execute();
        String tipMinusOne = IdentifyCommand.on(Bot.upstreamRepo).id().rev("-2").execute();
        if (tipMinusOne.equals(tipBeforeImport)) {
            logger.debug("Rolling mercurial back to rev before patch import...");
            StripCommand.on(Bot.upstreamRepo).rev("-1").noBackup().execute();
        }

        // If we get this far, then we can set PR status to success.
        setPrStatus(PrStatus.SUCCESS, prNum, prShaHead, statusUrl, "Ready to merge with upstream.");

        return Response.ok().build();
    }

    /**
     * Fetches, extracts, and then returns the OCA signatures from Oracle's website.
     */
    private List<String> fetchOcaSignatures() throws IOException {
        List<String> signatures = new ArrayList<>();
        try {
            Document doc = Jsoup.connect("http://www.oracle.com/technetwork/community/oca-486395.html").get();
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
    private void setPrStatus(PrStatus status, String prNum, String prShaHead, String statusUrl, String description) {
        ObjectNode pendingStatus = JsonNodeFactory.instance.objectNode();
        pendingStatus.put("state", status.toString().toLowerCase(US));
        pendingStatus.put("target_url", Bot.BASE_URI.resolve("pr/" + prNum + "/" + prShaHead + "/index.html").toASCIIString());
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

    enum PrStatus {
        ERROR,
        FAILURE,
        PENDING,
        SUCCESS
    }

}
