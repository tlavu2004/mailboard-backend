package com.awad.emailclientai.modules.email.entity;

/**
 * Enum representing supported email providers with their default IMAP/SMTP settings.
 */
public enum EmailProvider {
    GMAIL("imap.gmail.com", 993, "smtp.gmail.com", 587),
    OUTLOOK("outlook.office365.com", 993, "smtp.office365.com", 587),
    YAHOO("imap.mail.yahoo.com", 993, "smtp.mail.yahoo.com", 587),
    ZOHO("imap.zoho.com", 993, "smtp.zoho.com", 587),
    ICLOUD("imap.mail.me.com", 993, "smtp.mail.me.com", 587),
    OTHER(null, 993, null, 587);

    private final String defaultImapHost;
    private final int defaultImapPort;
    private final String defaultSmtpHost;
    private final int defaultSmtpPort;

    EmailProvider(String defaultImapHost, int defaultImapPort, 
                  String defaultSmtpHost, int defaultSmtpPort) {
        this.defaultImapHost = defaultImapHost;
        this.defaultImapPort = defaultImapPort;
        this.defaultSmtpHost = defaultSmtpHost;
        this.defaultSmtpPort = defaultSmtpPort;
    }

    public String getDefaultImapHost() {
        return defaultImapHost;
    }

    public int getDefaultImapPort() {
        return defaultImapPort;
    }

    public String getDefaultSmtpHost() {
        return defaultSmtpHost;
    }

    public int getDefaultSmtpPort() {
        return defaultSmtpPort;
    }
}
