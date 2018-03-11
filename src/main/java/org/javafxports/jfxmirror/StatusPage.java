package org.javafxports.jfxmirror;

public class StatusPage {
    public static String getStatusPageHtml(String prNum, String prShaHead, boolean signedOca) {
        return " <!DOCTYPE html>\n" +
                "<html>\n" +
                "  <head>\n" +
                "    <meta charset=\"UTF-8\">\n" +
                "    <title>Status: PR #" + prNum + " (" + prShaHead + ")</title>\n" +
                "  </head>\n" +
                "  <body>\n" +
                "      OCA: " + (signedOca ? "Signed" : "Not signed") + "\n" +
                "      JBS Bug:" +
                "      Mercurial Patch: <a href=\"./patch/" + prNum + ".patch\">View</a>\n" +
                "      Webrev: <a href=\"./webrev\">View</a> | <a href=\"./webrev.zip\">Download</a>\n" +
                "      jcheck:" +
                "  </body>\n" +
                "</html>\n";
    }
}
