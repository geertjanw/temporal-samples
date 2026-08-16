package com.example.security;

import org.apache.shiro.SecurityUtils;
import org.apache.shiro.authc.UsernamePasswordToken;
import org.apache.shiro.env.BasicIniEnvironment;
import org.apache.shiro.mgt.SecurityManager;
import org.apache.shiro.subject.SimplePrincipalCollection;
import org.apache.shiro.subject.Subject;

/**
 * Small helper around Apache Shiro.
 *
 * Two responsibilities:
 *  1. Interactive login at the "edge" (the client that starts workflows).
 *  2. Re-hydrating a Subject from a plain username inside Temporal activity
 *     workers, so that permission checks can be enforced deep inside a
 *     workflow even though the workflow itself only carries the principal
 *     name (never a session or password) through its (persisted) input.
 */
public final class ShiroSecurity {

    /** Realm name used by IniRealm when loading shiro.ini. */
    private static final String INI_REALM = "iniRealm";

    private ShiroSecurity() {}

    /** Load shiro.ini and install the global SecurityManager. Call once at startup. */
    public static void init() {
        SecurityManager securityManager =
                new BasicIniEnvironment("classpath:shiro.ini").getSecurityManager();
        SecurityUtils.setSecurityManager(securityManager);
    }

    /** Authenticate a user with username/password (the "edge" login). */
    public static Subject login(String username, String password) {
        Subject subject = SecurityUtils.getSubject();
        subject.login(new UsernamePasswordToken(username, password));
        return subject;
    }

    /**
     * Build a Subject for an already-authenticated principal.
     *
     * Temporal activities run on worker threads (possibly on other machines),
     * so the original thread-bound Subject from login() is not available.
     * The workflow input carries only the username; this method turns it back
     * into a Subject against the same realm so isPermitted()/hasRole() work.
     */
    public static Subject subjectFor(String username) {
        return new Subject.Builder(SecurityUtils.getSecurityManager())
                .principals(new SimplePrincipalCollection(username, INI_REALM))
                .authenticated(true)
                .buildSubject();
    }

    /** Convenience permission check for a propagated principal. */
    public static boolean isPermitted(String username, String permission) {
        return subjectFor(username).isPermitted(permission);
    }
}
