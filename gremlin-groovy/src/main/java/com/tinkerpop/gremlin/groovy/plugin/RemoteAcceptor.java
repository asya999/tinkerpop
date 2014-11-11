package com.tinkerpop.gremlin.groovy.plugin;

import org.codehaus.groovy.tools.shell.Groovysh;

import java.io.Closeable;
import java.util.List;

/**
 * @author Stephen Mallette (http://stephen.genoprime.com)
 */
public interface RemoteAcceptor extends Closeable {

    public static final String RESULT = "result";

    /**
     * Gets called when :remote is used in conjunction with the "connect" option.  It is up to the implementation
     * to decide how additional arguments on the line should be treated after "connect".
     *
     * @return an object to display as output to the user
     * @throws com.tinkerpop.gremlin.groovy.plugin.RemoteException if there is a problem with connecting
     */
    public Object connect(final List<String> args) throws RemoteException;

    /**
     * Gets called when :remote is used in conjunction with the "config" option.  It is up to the implementation
     * to decide how additional arguments on the line should be treated after "config".
     */
    public Object configure(final List<String> args);

    /**
     * Gets called when :submit is executed.  It is up to the implementation to decide how additional arguments on
     * the line should be treated after "submit".
     */
    public Object submit(final List<String> args);

    public static String getScript(final String submittedScript, final Groovysh shell) {
        return submittedScript.startsWith("@") ? shell.getInterp().getContext().getProperty(submittedScript.substring(1)).toString() : submittedScript;
    }
}
