package org.javafxports.jfxmirror;

public class StatusPage {
    public static String getStatusPageHtml(String prNum, String prShaHead) {
        return " <!DOCTYPE html>\n" +
                "<html>\n" +
                "  <head>\n" +
                "    <meta charset=\"UTF-8\">\n" +
                "    <title>Status: PR #" + prNum + " (" + prShaHead + ")</title>\n" +
                "  </head>\n" +
                "  <body>\n" +
                "      OCA:" +
                "      JBS Bug:" +
                "      Mercurial Patch: <a href=\"./patch/" + prNum + ".patch\">View</a>" +
                "      Webrev: <a href=\"./webrev\">View</a> | <a href=\"./webrev.zip\">Download</a>" +
                "      jcheck:" +
                "  </body>\n" +
                "</html>\n";
    }
}
