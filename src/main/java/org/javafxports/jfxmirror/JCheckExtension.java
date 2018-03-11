package org.javafxports.jfxmirror;

import java.nio.file.Paths;

import com.aragost.javahg.MercurialExtension;

public class JCheckExtension extends MercurialExtension {
    @Override
    public String getName() {
        return "jcheck";
    }

    @Override
    public String getPath() {
        return Paths.get(System.getProperty("user.home"), "jfxmirror", "jcheck.py").toString();
    }
}
