package com.awad.emailclientai.modules.email.entity;

/**
 * Enum representing the authentication type used to connect to an email server.
 */
public enum EmailAuthType {
    /**
     * Basic authentication using username and password (or App Password).
     */
    BASIC,
    
    /**
     * OAuth2 authentication using access/refresh tokens.
     * Typically used for providers like Gmail and Outlook that support XOAUTH2.
     */
    OAUTH2
}
