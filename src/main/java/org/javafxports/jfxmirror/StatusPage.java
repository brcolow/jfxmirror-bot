package org.javafxports.jfxmirror;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.stream.Collectors;

public class StatusPage {

    public static void createStatusPageHtml(PullRequestContext pullRequestContext) throws IOException {
        Path statusPath = Paths.get(System.getProperty("user.home"), "jfxmirror", "pr",
                pullRequestContext.getPrNum(), pullRequestContext.getPrShaHead());
        if (!Files.exists(statusPath)) {
            Files.createDirectories(statusPath);
        }
        String statusPage = " <!DOCTYPE html>\n" +
                "<html>\n" +
                "  <head>\n" +
                "    <meta charset=\"UTF-8\">\n" +
                "    <title>Status: PR #" + pullRequestContext.getPrNum() + " (" + pullRequestContext.getPrShaHead() + ")</title>\n" +
                "  </head>\n" +
                "  <body>\n" +
                "      <p>OCA: " + pullRequestContext.getOcaStatus().getDescription() + "</p>" +
                "      <p>JBS Bug(s): " + getJbsBugHtml(pullRequestContext.getJbsBugsReferenced(), pullRequestContext.getJbsBugsReferencedButNotFound()) + "</p>" +
                "      <p>Mercurial Patch: <a href=\"./patch/" + pullRequestContext.getPrNum() + ".patch\">View</a></p>" +
                "      <p>Webrev: <a href=\"./webrev/\">View</a> | <a href=\"./webrev.zip\">Download</a></p>" +
                "      <p>jcheck: <a href=\"./jcheck.txt\">View</a></p>" +
                "  </body>\n" +
                "</html>\n";
        Files.write(statusPath.resolve("index.html"), statusPage.getBytes(UTF_8));
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
                    .append("<li>Status is one of \"Open\", \"In Progress\", \"New\", \"Provisional\".</li>");
        }

        return jbsHtmlBuilder.toString();
    }
}
