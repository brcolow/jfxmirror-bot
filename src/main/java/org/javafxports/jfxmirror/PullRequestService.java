package org.javafxports.jfxmirror;

import java.util.Locale;

import javax.ws.rs.Consumes;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

public class PullRequestService {
    @POST
    @Path("/pr")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response handlePullRequest(ObjectNode pullRequestEvent) {
        // https://developer.github.com/v3/activity/events/types/#pullrequestevent

        if (!pullRequestEvent.has("action") || !pullRequestEvent.has("pull_request")) {
            return Response.status(Response.Status.BAD_REQUEST).entity(new ObjectNode(JsonNodeFactory.instance)
                    .put("error", "invalid arguments: must POST pull-request event to /pr"))
                    .type(MediaType.APPLICATION_JSON_TYPE).build();
        }

        String action = pullRequestEvent.get("action").asText();

        // "assigned", "unassigned", "review_requested", "review_request_removed", "labeled", "unlabeled", "opened", "edited", "closed", or "reopened"
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
        String username = pullRequest.get("user").get("login").asText();
        String diffUrl = pullRequest.get("diff_url").asText();
        String patchUrl = pullRequest.get("patch_url").asText();


        return Response.ok().build();
    }
}
