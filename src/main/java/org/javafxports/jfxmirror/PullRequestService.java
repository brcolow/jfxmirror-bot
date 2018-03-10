package org.javafxports.jfxmirror;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Scanner;

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

import com.aragost.javahg.commands.ExecutionException;
import com.aragost.javahg.commands.ImportCommand;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

@Path("/")
public class PullRequestService {

    private static final java.nio.file.Path staticBasePath = Paths.get(System.getProperty("user.home"), "jfxmirror");
    private static final String osName = System.getProperty("os.name").toLowerCase(Locale.US);
    private static final String githubApi = "https://api.github.com";
    private static final String githubAccept = "application/vnd.github.v3+json";
    private static final String githubAccessToken = System.getenv("jfxmirror_gh_token");
    private static final Logger logger = LoggerFactory.getLogger(PullRequestService.class);

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
        return Response.ok(staticBasePath.resolve("pr").resolve(path + "." + ext).toFile()).build();
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
        return Response.ok(staticBasePath.resolve("pr").resolve(path).resolve("index.html").toFile()).build();
    }

    @POST
    @Path("/pr")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response handlePullRequest(ObjectNode pullRequestEvent, @Context ContainerRequestContext requestContext) {

        MultivaluedMap<String, String> headers = requestContext.getHeaders();
        if (!headers.containsKey("X-GitHub-Event") || headers.get("X-GitHub-Event").size() != 1) {
            logger.error("Got POST to /pr but request did not have \"X-GitHub-Event\" header");
            return Response.status(Response.Status.BAD_REQUEST).entity(new ObjectNode(JsonNodeFactory.instance)
                    .put("error", "\"X-GitHub-Event\" header was not present or had multiple values"))
                    .type(MediaType.APPLICATION_JSON_TYPE).build();
        }

        String gitHubEvent = headers.getFirst("X-GitHub-Event");
        if (!gitHubEvent.equalsIgnoreCase("ping") && !gitHubEvent.equalsIgnoreCase("pull_request")) {
            logger.error("Got POST to /pr but \"X-GitHub-Event\" header was not one of \"ping\", \"pull_request\" but was: " + gitHubEvent);
            logger.debug("Make sure that the only checked trigger event for the jfxmirror_bot webhook is \"Pull request\"");
            return Response.status(Response.Status.BAD_REQUEST).entity(new ObjectNode(JsonNodeFactory.instance)
                    .put("error", "\"X-GitHub-Event\" header was not one of \"ping\", \"pull_request\" but was: " + gitHubEvent))
                    .type(MediaType.APPLICATION_JSON_TYPE).build();
        }

        if (gitHubEvent.equalsIgnoreCase("ping")) {
            logger.info("\u2713 Pinged by GitHub, webhook appears to be correctly configured.");
            return Response.ok().entity("pong").build();
        }

        // If we get this far then we must be dealing with a pull_request event.
        // https://developer.github.com/v3/activity/events/types/#pullrequestevent
        String action = pullRequestEvent.get("action").asText();

        // "assigned", "unassigned", "review_requested", "review_request_removed", "labeled", "unlabeled", "opened",
        // "edited", "closed", or "reopened"
        switch (action.toLowerCase(Locale.US)) {
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
        String statusUrl = String.format("%s/repos/%s/%s/statuses/%s", githubApi, repoFullName[0], repoFullName[1], prShaHead);

        // Set the status of the PR to pending while we do the necessary checks.
        setPrStatus(PrStatus.PENDING, statusUrl, "Checking for upstream mergeability...");

        // TODO: Before converting the PR patch, should it be squashed to one commit of concatenated commit messages?
        // Currently we lose the commit messages of all but the first commit.
        String patchUrl = pullRequest.get("patch_url").asText();
        String hgPatch;
        try {
            hgPatch = convertGitPatchToHgPatch(patchUrl);
        } catch (IOException | MessagingException e) {
            setPrStatus(PrStatus.ERROR, statusUrl, "Could not convert git patch to hg patch.");
            logger.error("\u2718 Encountered error trying to convert git patch to hg patch.");
            logger.debug("exception: ", e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
        }

        String patchFilename = patchUrl.substring(patchUrl.lastIndexOf('/') + 1);
        java.nio.file.Path patchesDir = Paths.get(System.getProperty("user.home"), "jfxmirror", prNum, prShaHead, "patch");
        if (!patchesDir.toFile().exists()) {
            try {
                Files.createDirectories(patchesDir);
            } catch (IOException e) {
                setPrStatus(PrStatus.ERROR, statusUrl, "Could not create patches directory.");
                logger.error("\u2718 Could not create patches directory: " + patchesDir);
                logger.debug("exception: ", e);
                return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
            }
        }
        java.nio.file.Path hgPatchPath = patchesDir.resolve(patchFilename);
        try {
            Files.write(hgPatchPath, hgPatch.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            setPrStatus(PrStatus.ERROR, statusUrl, "Could not write hg patch to file.");
            logger.error("\u2718 Could not write hg patch to file.");
            logger.debug("exception: ", e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
        }

        // Apply the hg patch to the upstream hg repo
        try {
            ImportCommand importCommand = ImportCommand.on(Bot.upstreamRepo);
            // importCommand.cmdAppend("--no-commit");
            importCommand.execute(hgPatchPath.toFile());
        } catch (IOException | ExecutionException e) {
            setPrStatus(PrStatus.FAILURE, statusUrl, "Could not apply PR changeset to upstream hg repository.");
            logger.error("\u2718 Could not apply PR changeset to upstream mercurial repository.");
            logger.debug("exception: ", e);
            // return Response.status(Response.Status.BAD_REQUEST).build();
        }

        // Check if user who opened PR has signed the OCA http://www.oracle.com/technetwork/community/oca-486395.html
        // We will keep a simple file of OCA confirmations where each line maps a github username (and user id?) to
        // their listed name on the OCA page.
        String username = pullRequest.get("user").get("login").asText();
        logger.debug("Checking if \"" + username + "\" has signed the OCA...");

        java.nio.file.Path ocaFile = Paths.get(System.getProperty("user.home"), "jfxmirror", "oca.txt");
        boolean signedOca = false;
        String ocaName;
        try {
            List<String> ocaFileLines = Files.readAllLines(ocaFile, StandardCharsets.UTF_8);
            for (String line : ocaFileLines) {
                String[] gitHubUsernameOcaName = line.split("@@@");
                if (gitHubUsernameOcaName.length != 2) {
                    setPrStatus(PrStatus.ERROR, statusUrl, "OCA signature file malformed.");
                    logger.error("\u2718 OCA signature file malformed (expecting separator \"@@@\").");
                    logger.debug("Bad line: " + line);
                    return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
                }

                if (gitHubUsernameOcaName[0].equals(username)) {
                    signedOca = true;
                    ocaName = gitHubUsernameOcaName[1];
                    logger.info("\u2713 User who opened PR is known to have signed OCA under name: " + ocaName);
                    break;
                }
            }
        } catch (IOException e) {
            setPrStatus(PrStatus.ERROR, statusUrl, "Could not read OCA signatures file.");
            logger.error("\u2718 Could not read OCA signatures file.");
            logger.debug("exception: ", e);
            return Response.status(Response.Status.BAD_REQUEST).build();
        }

        if (!signedOca) {
            // We have no record that the user who opened this PR signed the OCA so let's check Oracle's OCA
            // page to see if we can find their username.
            boolean foundUsername = false;
            String ocaLine = null;
            try {
                Document doc = Jsoup.connect("http://www.oracle.com/technetwork/community/oca-486395.html").get();
                Elements signatoryLetters = doc.select(".dataTable > tbody:nth-child(1) > tr:nth-child(1) > td:nth-child(1) > ul:nth-child(2n+5)");
                for (Element signatoryLetter : signatoryLetters) {
                    Elements signatories = signatoryLetter.select("li");
                    for (Element signatory : signatories) {
                        // See if the signature line contains their github username.
                        // FIXME: This is a really imperfect way to do this, but it was the best I could think of quickly.
                        for (String split : signatory.text().split(" - ")) {
                            if (split.equalsIgnoreCase(username)) {
                                foundUsername = true;
                                ocaLine = signatory.text();
                                break;
                            }
                        }
                    }
                }
            } catch (Selector.SelectorParseException | IOException e) {
                logger.error("\u2718 Could not extract list of OCA signatures.");
                logger.debug("Check the CSS selector as the HTML of the OCA page may have changed.");
                logger.debug("exception: ", e);
            }

            String commentsUrl = pullRequestEvent.get("_links").get("comments").get("href").asText();
            String comment = "@" + username + " ";
            if (foundUsername) {
                logger.debug("Found GitHub username of user who opened PR on OCA signature list.");
                comment += "We attempted to determine if you have signed the Oracle Contributor Agreement (OCA) and found " +
                        "a signatory with your GitHub username:\n`" + ocaLine + "`\nIf that's you, add a comment on " +
                        "this PR saying \"@jfxmirror_bot Yes, that's me\". Otherwise, if that's not you:\n\n";
            } else {
                // Post comment on PR telling them we could not find their github username listed on OCA signature page,
                // ask them if they have signed it.
                comment += "We attempted to determine if you have signed the Oracle Contributor Agreement (OCA) but could " +
                        "not find a signature line with your GitHub username.\n\n";
            }
            comment += "* If you have already signed the OCA: " +
                    "\tAdd a comment on this PR saying \"@jfxmirror_bot I have signed the OCA, my signature line is \"`{signature_line}`\"\n" +
                    "\twhere `{signature_line}` is a line from the OCA signatures list, such as \"Michael Ennen - GlassFish Jersey - brcolow\"\n\n" +
                    "* If you have not yet signed the OCA:\n" +
                    "\tFollow the instructions at http://www.oracle.com/technetwork/community/oca-486395.html for " +
                    "doing so. Once you have signed the OCA and your name has been added to the list of signatures, " +
                    "add a comment on this PR saying \"@jfxmirror_bot I have now signed the OCA with GitHub username \"`{github_username}`\"\".";

            // TODO: Add listeners, or some type of callback, for checking for the above comment replies. Possible replies:
            // 1.) @jfxmirror_bot Yes, that's me
            // 2.) @jfxmirror_bot I have signed the OCA, my signature line is \"signature line\"
            // 3.) @jfxmirror_bot I have now signed the OCA with GitHub username \"username\"
            // In order to make this work we need to add a checkmark to the \"IssueCommentEvent\" webhook on GitHub settings
            // (make sure to add that to README) and then make a jersey endpoint for issue comments.
            // See: https://developer.github.com/v3/activity/events/types/#issuecommentevent

            Response commentResponse = Bot.httpClient.target(commentsUrl)
                    .request()
                    .header("Authorization", "token " + githubAccessToken)
                    .accept(githubAccept)
                    .post(Entity.json(JsonNodeFactory.instance.objectNode().put("body", comment)));

            if (commentResponse.getStatus() == 404) {
                logger.error("\u2718 Could not post comment on PR #" + prNum + " for assisting the user who opened the " +
                        "PR with confirming their signing of the OCA.");
                logger.debug("GitHub response: " + commentResponse.getEntity());
                setPrStatus(PrStatus.ERROR, statusUrl, "Could not post comment to PR.");
                return Response.status(Response.Status.BAD_REQUEST).build();
            }
        }

        // See if there is a JBS bug associated with this PR (how? commit message? rely on jcheck?)
        // http://openjdk.java.net/guide/producingChangeset.html#changesetComment states that the "changeset message"
        // should be of the form "<bugid>: <synopsis-of-symptom>" so we could grab it from that

        // Run jcheck http://openjdk.java.net/projects/code-tools/jcheck/
        // This looks to be a mercurial extension. javahg should support those, see for example:
        // ExtensionTest.java: https://bitbucket.org/aragost/javahg/src/tip/src/test/java/com/aragost/javahg/ExtensionTest.java
        // MercurialExtension.java: https://bitbucket.org/aragost/javahg/src/tip/src/main/java/com/aragost/javahg/MercurialExtension.java

        // Generate a webrev
        java.nio.file.Path webRevOutputPath = Paths.get(System.getProperty("user.home"), "jfxmirror", "pr", prNum, prShaHead);

        ProcessBuilder processBuilder;
        if (osName.contains("windows")) {
            // Calling ksh to generate webrev requires having bash in the Windows %PATH%, this works on e.g. WSL.
            String kshInvocation = "\"ksh " +
                    Paths.get(System.getProperty("user.home"), "jfxmirror", "webrev", "webrev.ksh").toString()
                            .replaceFirst("C:\\\\", "/mnt/c/").replaceAll("\\\\", "/")
                    + " -N -m -o " + webRevOutputPath.toString()
                    .replaceFirst("C:\\\\", "/mnt/c/").replaceAll("\\\\", "/") + "\"";
            logger.info("invocation: " + kshInvocation);
            processBuilder = new ProcessBuilder("bash", "-c", kshInvocation);
        } else {
            // Just call ksh directly.
            processBuilder = new ProcessBuilder("ksh",
                    Paths.get(System.getProperty("user.home"), "jfxmirror", "webrev", "webrev.ksh").toString(),
                    "-N", "-m",
                    "-o", webRevOutputPath.toString());
        }
        // TODO: Add -c argument for the bug ID when we implenment JBS bugs
        processBuilder.directory(Bot.upstreamRepo.getDirectory());
        try {
            processBuilder.inheritIO();
            logger.debug("Generating webrev for PR #" + prNum + " (" + prShaHead + ")...");
            Process webrev = processBuilder.start();
        } catch (IOException e) {
            setPrStatus(PrStatus.ERROR, statusUrl, "Could not generate webrev for PR.");
            logger.error("\u2718 Encountered error trying to generate webrev.");
            logger.debug("exception: ", e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
        }

        // Make status page at "prNum/prShaHead" from the above data, set status to success/fail depending on the above
        java.nio.file.Path statusPath = Paths.get(System.getProperty("user.home"), "jfxmirror", prNum, prShaHead);
        if (!statusPath.toFile().exists()) {
            try {
                Files.createDirectories(statusPath);
            } catch (IOException e) {
                logger.error("\u2718 Could not create directory: " + statusPath);
                logger.debug("exception: ", e);
            }
        }

        // Create status index page (that is linked to by the jfxmirror_bot PR status check.
        String statusPage = StatusPage.getStatusPageHtml(prNum, prShaHead);
        try {
            Files.write(statusPath.resolve("index.html"), statusPage.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            logger.error("\u2718 Could not write \"index.html\" to: " + statusPath);
            logger.debug("exception: ", e);
            setPrStatus(PrStatus.ERROR, statusUrl, "Could not write status page.");
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
        }

        // If we get this far, then we can set PR status to success.
        setPrStatus(PrStatus.SUCCESS, statusUrl, "Ready to merge with upstream.");

        return Response.ok().build();
    }

    private void setPrStatus(PrStatus status, String statusUrl, String description) {
        ObjectNode pendingStatus = JsonNodeFactory.instance.objectNode();
        pendingStatus.put("state", status.toString().toLowerCase(Locale.US));
        pendingStatus.put("target_url", statusUrl);
        pendingStatus.put("description", description);
        pendingStatus.put("context", "jfxmirror_bot");

        Response statusResponse = Bot.httpClient.target(statusUrl)
                .request()
                .header("Authorization", "token " + githubAccessToken)
                .accept(githubAccept)
                .post(Entity.json(pendingStatus.toString()));

        if (statusResponse.getStatus() == 404) {
            logger.error("GitHub API authentication failed, are you sure the \"jfxmirror_gh_token\"\n" +
                    "environment variable is set correctly?");
            Bot.cleanup();
            System.exit(1);
        }
    }

    /**
     * Based on https://github.com/mozilla/moz-git-tools/blob/master/git-patch-to-hg-patch
     */
    private static String convertGitPatchToHgPatch(String patchUrl) throws IOException, MessagingException {
        String gitPatch = new Scanner(new URL(patchUrl).openStream(), "UTF-8").useDelimiter("\\A").next();
        Session session = Session.getDefaultInstance(new Properties());
        MimeMessage emailMessage = new MimeMessage(session, new ByteArrayInputStream(gitPatch.getBytes(StandardCharsets.UTF_8)));
        Map<String, String> headers = enumToMap(emailMessage.getAllHeaders());
        return "# HG changeset patch\n" +
                "# User " + headers.get("From") + "\n" +
                "# Date " + headers.get("Date") + "\n\n" +
                emailMessage.getSubject().replaceAll("^\\[PATCH( \\d+/\\d+)?\\] ", "") + "\n\n" +
                emailMessage.getContent().toString().replaceAll("--\\s?\\n[0-9\\.]+\\n$", "");
    }

    private static Map<String, String> enumToMap(Enumeration<Header> headers) {
        HashMap<String, String> headersMap = new HashMap<>();
        while (headers.hasMoreElements()) {
            Header header = headers.nextElement();
            headersMap.put(header.getName(), header.getValue());
        }
        return headersMap;
    }

    enum PrStatus {
        ERROR,
        FAILURE,
        PENDING,
        SUCCESS
    }
}
