package com.awad.emailclientai.modules.user.entity;

/**
 * Enum representing the authentication provider used by a user.
 * Determines what email accounts the user can access.
 */
public enum AuthProvider {
    /**
     * Local registration with email/password.
     * Users can link multiple external email accounts.
     */
    LOCAL,

    /**
     * Google OAuth login.
     * Users can only access their Gmail account (Siloed Mode).
     */
    GOOGLE,

    /**
     * Microsoft OAuth login (Future).
     * Users can only access their Outlook account (Siloed Mode).
     */
    MICROSOFT
}
