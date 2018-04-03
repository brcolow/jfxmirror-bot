package org.javafxports.jfxmirror;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

public class StatusPage {

    public static void createStatusPageHtml(PullRequestContext pullRequestContext) throws IOException {
        // assert (pullRequestContext.getPrStatus() == SUCCESS || FAILURE)
        Path statusPath = Paths.get(System.getProperty("user.home"), "jfxmirror", "pr",
                pullRequestContext.getPrNum(), pullRequestContext.getPrShaHead());
        if (!Files.exists(statusPath)) {
            Files.createDirectories(statusPath);
        }
        StringBuilder statusPageBuilder = new StringBuilder();
        statusPageBuilder
                .append("<!DOCTYPE html>\n")
                .append("<html>\n")
                .append("  <head>\n")
                .append("    <meta charset=\"UTF-8\">\n")
                .append("    <title>Status: PR #").append(pullRequestContext.getPrNum()).append(" (").append(pullRequestContext.getPrShaHead()).append(")</title>\n")
                .append("  </head>\n")
                .append("  <body>\n")
                .append("    <p style=\"color:").append(
                pullRequestContext.getPrStatus() == PrStatus.SUCCESS ? "green" : "red").append("\">").append(
                pullRequestContext.getPrStatus().name().charAt(0) + pullRequestContext.getPrStatus().name().toLowerCase(
                        Locale.US).substring(1)).append("!</p>\n");
        statusPageBuilder
                .append("    <p>Mercurial Patch: <a href=\"./patch/").append(pullRequestContext.getPrNum()).append(".patch\">View</a></p>\n")
                .append("    <p>OCA: ").append(pullRequestContext.getOcaStatus().getDescription()).append("</p>\n")
                .append("    <p>JBS Bug(s): ").append(getJbsBugHtml(pullRequestContext.getJbsBugsReferenced(), pullRequestContext.getJbsBugsReferencedButNotFound())).append("</p>\n");

        if (pullRequestContext.getPrStatus() == PrStatus.SUCCESS) {
            statusPageBuilder.append(
                    "    <p>Webrev: <a href=\"./webrev/\">View</a> | <a href=\"./webrev.zip\">Download</a></p>\n" +
                    "    <p>jcheck: <a href=\"./jcheck.txt\">View</a></p>\n");
        }

        if (!pullRequestContext.getRejects().isEmpty()) {
            statusPageBuilder.append(
                    "    <p>Patch rejects: " + getRejectsHtml(pullRequestContext.getRejects()) + "</p>\n");
        }
        statusPageBuilder.append("  </body>\n").append("</html>\n");
        Files.write(statusPath.resolve("index.html"), statusPageBuilder.toString().getBytes(UTF_8));
    }

    private static String getRejectsHtml(List<Path> rejects) {
        return rejects.stream().map(reject -> "<a href=\"" + reject.getFileName().toString() + "\">" +
                reject.getFileName().toString() + "</a>").collect(Collectors.joining("|"));
    }

    private static String getJbsBugHtml(Collection<String> jbsBugsReferenced,
                                        Collection<String> jbsBugsReferencedButNotFound) {
        if (jbsBugsReferenced.isEmpty()) {
            return "No JBS bugs referenced in PR commit message, branch name, or title.";
        }

        StringBuilder jbsHtmlBuilder = new StringBuilder();
        jbsHtmlBuilder.append("JBS Bugs referenced by PR:<br><br>");
        jbsHtmlBuilder.append(jbsBugsReferenced.stream().map(jbsBug ->
                "<a href=\"https://bugs.openjdk.java.net/browse/" + jbsBug + "\">" + jbsBug + "</a>").collect(
                Collectors.joining("|")));

        if (!jbsBugsReferencedButNotFound.isEmpty()) {
            jbsHtmlBuilder.append("JBS Bugs referenced by PR that could not be found:<br><br>");
            jbsHtmlBuilder.append(jbsBugsReferencedButNotFound.stream().collect(Collectors.joining(",")));
            jbsHtmlBuilder.append("<br>Make sure for each of the above bugs that they are:<br>")
                    .append("<ul><li>For the \"javafx\" component.</li>")
                    .append("<li>Status is one of \"Open\", \"In Progress\", \"New\", \"Provisional\".</li></ul>");
        }

        return jbsHtmlBuilder.toString();
    }
}
