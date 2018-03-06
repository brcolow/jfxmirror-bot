package org.javafxports.jfxmirror;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.net.URL;
import java.util.Scanner;

import javax.script.ScriptContext;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;
import javax.script.SimpleScriptContext;

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

        // See if there is a JBS bug associated with this PR. (How? PR labels? Don't think they provide a way to set a message)

        // Convert the pull-request from a git patch to an hg patch.
        try {
            String hgPatch = convertGitPatchToHgPatch(patchUrl);
        } catch (ScriptException e) {
            context.getLogger().log("error: " + e.getMessage());
        }

        // Use the hg patch to generate a webrev on the latest master of upstream OpenJFX.
    }

    private static void checkContributorAgreement(String username) {

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
