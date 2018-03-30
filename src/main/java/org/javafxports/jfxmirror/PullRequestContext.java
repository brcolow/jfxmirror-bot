package org.javafxports.jfxmirror;

import java.util.Objects;

import com.fasterxml.jackson.databind.JsonNode;

public class PullRequestContext {

    private final JsonNode pullRequest;
    private final String prNum;
    private final String prShaHead;
    private final String statusUrl;

    public PullRequestContext(JsonNode pullRequest, String prNum, String prShaHead, String statusUrl) {
        Objects.requireNonNull(pullRequest, "pullRequest must not be null");
        Objects.requireNonNull(prNum, "prNum must not be null");
        Objects.requireNonNull(prShaHead, "prShaHead must not be null");
        Objects.requireNonNull(statusUrl, "statusUrl must not be null");
        this.pullRequest = pullRequest;
        this.prNum = prNum;
        this.prShaHead = prShaHead;
        this.statusUrl = statusUrl;
    }

    public JsonNode getPullRequest() {
        return pullRequest;
    }

    public String getPrNum() {
        return prNum;
    }

    public String getPrShaHead() {
        return prShaHead;
    }

    public String getStatusUrl() {
        return statusUrl;
    }
}
