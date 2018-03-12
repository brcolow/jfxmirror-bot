package org.javafxports.jfxmirror;

import java.nio.file.Paths;

import com.aragost.javahg.MercurialExtension;

/**
 * MercurialExtension implementation that allows for running jcheck on the upstream
 * OpenJFX repository. This is the javahg equivalent of adding:
 * <p>
 * <pre>{@code
 * [extensions]
 *   jcheck = $DIR/jcheck.py
 * }</pre>
 * <p>
 * to {@literal ~/.hgrc}.
 *
 * @see <a href="http://openjdk.java.net/projects/code-tools/jcheck/">Code Tools: jcheck</a>
 */
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
