package org.javafxports.jfxmirror;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.io.StringWriter;
import java.net.URL;
import java.util.Locale;
import java.util.Scanner;

import javax.script.ScriptContext;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;
import javax.script.SimpleScriptContext;
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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

@Path("/")
public class PullRequestService {

    private static final String githubApi = "https://api.github.com";
    private static final String githubAccept = "application/vnd.github.v3+json";
    private static final String githubAccessToken = System.getenv("jfxmirror_gh_token");

    @POST
    @Path("/pr")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response handlePullRequest(ObjectNode pullRequestEvent, @Context ContainerRequestContext requestContext) {

        MultivaluedMap<String, String> headers = requestContext.getHeaders();
        if (!headers.containsKey("X-GitHub-Event") || headers.get("X-GitHub-Event").size() != 1) {
            System.err.println("Got POST to /pr but request did not have \"X-GitHub-Event\" header");
            return Response.status(Response.Status.BAD_REQUEST).entity(new ObjectNode(JsonNodeFactory.instance)
                    .put("error", "\"X-GitHub-Event\" header was not present or had multiple values"))
                    .type(MediaType.APPLICATION_JSON_TYPE).build();
        }

        String gitHubEvent = headers.getFirst("X-GitHub-Event");
        if (!gitHubEvent.equalsIgnoreCase("ping") && !gitHubEvent.equalsIgnoreCase("pull_request")) {
            System.err.println("Got POST to /pr but \"X-GitHub-Event\" header was not one of \"ping\", \"pull_request\" but was: " + gitHubEvent);
            System.err.println("Make sure that the only checked trigger event for the jfxmirror_bot webhook is \"Pull request\"");
            return Response.status(Response.Status.BAD_REQUEST).entity(new ObjectNode(JsonNodeFactory.instance)
                    .put("error", "\"X-GitHub-Event\" header was not one of \"ping\", \"pull_request\" but was: " + gitHubEvent))
                    .type(MediaType.APPLICATION_JSON_TYPE).build();
        }

        if (gitHubEvent.equalsIgnoreCase("ping")) {
            System.out.println("Got ping request from GitHub: " + pullRequestEvent.get("zen"));
            System.out.println("Webhook should be good to go.");
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

        // Should the bot generate static content or should it be database driven?
        // If it's static, it might be challenging if we want to have an index page
        // that shows the historical reports.

        // Also, look at JBS for mercurial patches to compare to git patches.

        // Set the status of the PR to pending while we do the necessary checks.
        ObjectNode statusObject = JsonNodeFactory.instance.objectNode();
        statusObject.put("state", "pending");
        statusObject.put("target_url", "http://jfxmirror_bot.com/123");
        statusObject.put("description", "Checking for upstream mergeability...");
        statusObject.put("context", "jfxmirror_bot");

        Response statusResponse = Bot.httpClient.target(String.format(
                "%s/repos/%s/%s/statuses/%s", githubApi, "brcolow", "openjfx", "7e3bd03605db8298cfa7ce10835b40b8bef90466"))
                .request()
                .header("Authorization", "token " + githubAccessToken)
                .accept(githubAccept)
                .post(Entity.json(statusObject.toString()));

        if (statusResponse.getStatus() == 404) {
            System.err.println("GitHub API authentication failed, are you sure the \"jfxmirror_gh_token\"\n" +
                    "environment variable is set correctly?");
            Bot.cleanup();
            System.exit(1);
        }

        JsonNode pullRequest = pullRequestEvent.get("pull_request");
        String username = pullRequest.get("user").get("login").asText();
        String diffUrl = pullRequest.get("diff_url").asText();
        String patchUrl = pullRequest.get("patch_url").asText();

        // Convert the pull-request from a git patch to an hg patch.
        String hgPatch = null;
        try {
            hgPatch = convertGitPatchToHgPatch(patchUrl);
        } catch (IOException | ScriptException e) {
            System.err.println("Error: Could not convert git patch to hg patch:");
            e.printStackTrace();
        }

        System.out.println("\n\n-- HG PATCH --\n\n");
        System.out.println(hgPatch);
        return Response.ok().build();
    }

    /**
     * Takes as input the URL to a git patch and returns the equivalent hg patch.
     */
    private static String convertGitPatchToHgPatch(String patchUrl) throws ScriptException, IOException {
        String gitPatch = new Scanner(new URL(patchUrl).openStream(), "UTF-8").useDelimiter("\\A").next();
        StringWriter scriptOut = new StringWriter();
        ScriptEngineManager manager = new ScriptEngineManager();
        ScriptContext context = new SimpleScriptContext();
        context.setWriter(scriptOut);
        context.setReader(new StringReader(gitPatch));
        ScriptEngine engine = manager.getEngineByName("python");
        engine.eval(new BufferedReader(new InputStreamReader(
                Handler.class.getResourceAsStream("/git-patch-to-hg-patch"))), context);
        return scriptOut.toString();
    }
}
