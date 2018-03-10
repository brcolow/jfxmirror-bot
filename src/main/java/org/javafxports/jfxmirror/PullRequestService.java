package org.javafxports.jfxmirror;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Scanner;

import javax.mail.Header;
import javax.mail.MessagingException;
import javax.mail.Session;
import javax.mail.internet.MimeMessage;
import javax.ws.rs.Consumes;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
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

    private static final String osName = System.getProperty("os.name").toLowerCase(Locale.US);
    private static final String githubApi = "https://api.github.com";
    private static final String githubAccept = "application/vnd.github.v3+json";
    private static final String githubAccessToken = System.getenv("jfxmirror_gh_token");
    private static final Logger logger = LoggerFactory.getLogger(PullRequestService.class);

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
            logger.error("Make sure that the only checked trigger event for the jfxmirror_bot webhook is \"Pull request\"");
            return Response.status(Response.Status.BAD_REQUEST).entity(new ObjectNode(JsonNodeFactory.instance)
                    .put("error", "\"X-GitHub-Event\" header was not one of \"ping\", \"pull_request\" but was: " + gitHubEvent))
                    .type(MediaType.APPLICATION_JSON_TYPE).build();
        }

        if (gitHubEvent.equalsIgnoreCase("ping")) {
            logger.info("Got ping request from GitHub: " + pullRequestEvent.get("zen"));
            logger.info("Webhook should be good to go.");
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

        logger.info("Got pull request, with action: " + action);
        // Should the bot generate static content or should it be database driven?
        // If it's static, it might be challenging if we want to have an index page
        // that shows the historical reports.

        // Also, look at JBS for mercurial patches to compare to git patches.

        // Set the status of the PR to pending while we do the necessary checks.
        JsonNode pullRequest = pullRequestEvent.get("pull_request");
        String prNum = pullRequest.get("number").asText();
        String prShaHead = pullRequest.get("head").get("sha").asText();
        ObjectNode pendingStatus = JsonNodeFactory.instance.objectNode();
        pendingStatus.put("state", "pending");
        pendingStatus.put("target_url", String.format("http://jfxmirror_bot.com/%s/%s", prNum, prShaHead));
        pendingStatus.put("description", "Checking for upstream mergeability...");
        pendingStatus.put("context", "jfxmirror_bot");

        String[] repoFullName = pullRequestEvent.get("repository").get("full_name").asText().split("/");
        String statusUrl = String.format("%s/repos/%s/%s/statuses/%s", githubApi, repoFullName[0], repoFullName[1], prShaHead);
        Response statusResponse = Bot.httpClient.target(statusUrl)
                .request()
                .header("Authorization", "token " + githubAccessToken)
                .accept(githubAccept)
                .post(Entity.json(pendingStatus.toString()));

        logger.info("Status response: " + statusResponse);
        if (statusResponse.getStatus() == 404) {
            logger.error("GitHub API authentication failed, are you sure the \"jfxmirror_gh_token\"\n" +
                    "environment variable is set correctly?");
            Bot.cleanup();
            System.exit(1);
        }

        String username = pullRequest.get("user").get("login").asText();
        String diffUrl = pullRequest.get("diff_url").asText();
        String patchUrl = pullRequest.get("patch_url").asText();

        // TODO: Before converting the PR patch, should it be squashed to one commit of concatenated commit messages?
        // Currently we lose the commit messages of all but the first commit.
        String hgPatch;
        try {
            hgPatch = convertGitPatchToHgPatch(patchUrl);
        } catch (IOException | MessagingException e) {
            logger.error("Exception: " + e.getMessage());
            e.printStackTrace();
            // TODO: Don't return, set the PR status to failed with an explanation
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
        }

        String patchFilename = patchUrl.substring(patchUrl.lastIndexOf('/') + 1);
        java.nio.file.Path patchesDir = Paths.get(System.getProperty("user.home"), "jfxmirror", "patch", prNum, prShaHead);
        if (!patchesDir.toFile().exists()) {
            try {
                Files.createDirectories(patchesDir);
            } catch (IOException e) {
                logger.error("Could not create patches directory");
                e.printStackTrace();
                // TODO: Don't return, set the PR status to failed with an explanation
                return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
            }
        }
        java.nio.file.Path hgPatchPath = patchesDir.resolve(patchFilename);
        try {
            Files.write(hgPatchPath, hgPatch.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            logger.error("Could not write mercurial patch to file");
            e.printStackTrace();
            // TODO: Don't return, set the PR status to failed with an explanation
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
        }

        // Apply the hg patch to the upstream hg repo
        try {
            ImportCommand importCommand = ImportCommand.on(Bot.upstreamRepo);
            // importCommand.cmdAppend("--no-commit");
            importCommand.execute(hgPatchPath.toFile());
        } catch (IOException | ExecutionException e) {
            logger.error("Could not import changes to upstream mercurial repository:");
            e.printStackTrace();
        }

        logger.info("Checking if \"" + username + "\" has signed the OCA...");

        // Check if user who opened PR has signed the OCA http://www.oracle.com/technetwork/community/oca-486395.html
        // We will keep a simple file of OCA confirmations where each line maps a github username (and user id?) to
        // their listed name on the OCA page.

        // If "username" is in our oca.txt file:
        //   Status for OCA signature should be confirmed, list under what name
        // Else:
        //   See if we can find their username on the OCA list, if so, post a comment asking on the PR if that's them
        //   and how they should reply if it is/is not.
        //   If we can't find them on the list, tell them so and provide instructions for them to either give their OCA
        //   name or to sign the OCA.
        try {
            Document doc = Jsoup.connect("http://www.oracle.com/technetwork/community/oca-486395.html").get();
            Elements signatoryLetters = doc.select(".dataTable > tbody:nth-child(1) > tr:nth-child(1) > td:nth-child(1) > ul:nth-child(2n+5)");
            for (Element signatoryLetter : signatoryLetters) {
                Elements signatories = signatoryLetter.select("li");
                for (Element signatory : signatories) {
                    // logger.info("Signature: " + signatory.text());
                    // Split on "-" of signatory.text(), [0] will be the first/last name (or company name)
                }
            }
        } catch (Selector.SelectorParseException | IOException e) {
            e.printStackTrace();
        }


        // See if there is a JBS bug associated with this PR (how? commit message?)
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
            logger.info("Generating webrev for PR #" + prNum + " (" + prShaHead + ")...");
            Process webrev = processBuilder.start();
        } catch (IOException e) {
            logger.error("Error encountered generating webrev:");
            e.printStackTrace();
            // TODO: Don't return, set the PR status to failed with an explanation
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
        }

        // Make status page at "prNum/prShaHead" from the above data, set status to success/fail depending on the above

        return Response.ok().build();
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
}
