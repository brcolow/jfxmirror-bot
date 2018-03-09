package org.javafxports.jfxmirror;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;

import javax.script.ScriptContext;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;
import javax.script.SimpleScriptContext;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestStreamHandler;
import com.eclipsesource.json.Json;
import com.eclipsesource.json.JsonObject;
import com.eclipsesource.json.JsonValue;

public class Handler implements RequestStreamHandler {

    @Override
    public void handleRequest(InputStream inputStream, OutputStream outputStream, Context context) throws IOException {
        // https://developer.github.com/v3/activity/events/types/#pullrequestevent
        JsonObject pullRequestEvent = Json.parse(new InputStreamReader(inputStream, StandardCharsets.UTF_8)).asObject();

        String action = pullRequestEvent.get("action").asString();
        JsonObject pullRequest = pullRequestEvent.get("pull_request").asObject();
        String username = pullRequest.get("user").asObject().get("login").asString();
        String diffUrl = pullRequest.get("diff_url").asString();
        String patchUrl = pullRequest.get("patch_url").asString();

        if (pullRequest.get("labels") != null) {
            context.getLogger().log("This pull request has one or more labels");

            for (JsonValue label : pullRequest.get("labels").asArray()) {
                context.getLogger().log("Label name: " + label.asObject().get("name"));
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
