package org.javafxports.jfxmirror;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestStreamHandler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public class Handler implements RequestStreamHandler {

    @Override
    public void handleRequest(InputStream inputStream, OutputStream outputStream, Context context) throws IOException {
        // https://developer.github.com/v3/activity/events/types/#pullrequestevent
        JsonNode pullRequestEvent = new ObjectMapper().readTree(inputStream);

        String action = pullRequestEvent.get("action").asText();
        String username = pullRequestEvent.get("pull_request").get("user").get("login").asText();
        String diffUrl = pullRequestEvent.get("pull_request").get("diff_url").asText();
        String patchUrl = pullRequestEvent.get("pull_request").get("patch_url").asText();

        if (pullRequestEvent.get("pull_request").has("labels")) {
            context.getLogger().log("This pull request has one or more labels");

            for (JsonNode labelNode : pullRequestEvent.get("pull_request").get("labels")) {
                context.getLogger().log("Label name: " + labelNode.get("name"));
            }
        }
        context.getLogger().log("Action: " + action);
        context.getLogger().log("User: " + username);
        context.getLogger().log("pullRequestEvent: " + pullRequestEvent);

        // Check if username has signed OCA.

        if (action.equalsIgnoreCase("opened")) {
            checkContributorAgreement(username);
        }


    }

    private static void checkContributorAgreement(String username) {

    }


}
