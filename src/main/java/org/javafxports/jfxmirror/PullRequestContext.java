package org.javafxports.jfxmirror;

import java.util.Collections;
import java.util.Objects;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;

public class PullRequestContext {

    private final JsonNode pullRequest;
    private final String prNum;
    private final String prShaHead;
    private final String statusUrl;
    private OcaStatus ocaStatus;
    private Set<String> jbsBugsReferenced;
    private Set<String> jbsBugsReferencedButNotFound;

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

    public OcaStatus getOcaStatus() {
        return ocaStatus;
    }

    void setOcaStatus(OcaStatus ocaStatus) {
        this.ocaStatus = ocaStatus;
    }

    public Set<String> getJbsBugsReferenced() {
        return Collections.unmodifiableSet(jbsBugsReferenced);
    }

    void setJbsBugsReferenced(Set<String> jbsBugsReferenced) {
        this.jbsBugsReferenced = jbsBugsReferenced;
    }

    public Set<String> getJbsBugsReferencedButNotFound() {
        return Collections.unmodifiableSet(jbsBugsReferencedButNotFound);
    }

    void setJbsBugsReferencedButNotFound(Set<String> jbsBugsReferencedButNotFound) {
        this.jbsBugsReferencedButNotFound = jbsBugsReferencedButNotFound;
    }
}
