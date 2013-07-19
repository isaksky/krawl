package com.isaksky.krawl;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.regex.Pattern;


// Stolen wholesale from http://stackoverflow.com/a/15570670/371698
public class UriHelper {
    public static boolean checkForExternal(String str) {
        int length = str.length();
        for (int i = 0; i < length; i++) {
            if (str.charAt(i) > 0x7F) {
                return true;
            }
        }
        return false;
    }

    private static final Pattern COLON = Pattern.compile("%3A", Pattern.LITERAL);
    private static final Pattern SLASH = Pattern.compile("%2F", Pattern.LITERAL);
    private static final Pattern QUEST_MARK = Pattern.compile("%3F", Pattern.LITERAL);
    private static final Pattern EQUAL = Pattern.compile("%3D", Pattern.LITERAL);
    private static final Pattern AMP = Pattern.compile("%26", Pattern.LITERAL);

    public static String encodeUrl(String url) {
        if (checkForExternal(url)) {
            try {
                String value = URLEncoder.encode(url, "UTF-8");
                value = COLON.matcher(value).replaceAll(":");
                value = SLASH.matcher(value).replaceAll("/");
                value = QUEST_MARK.matcher(value).replaceAll("?");
                value = EQUAL.matcher(value).replaceAll("=");
                return AMP.matcher(value).replaceAll("&");
            } catch (UnsupportedEncodingException e) {
                throw new RuntimeException(e); //LOGGER.getIllegalStateException(e);
            }
        } else {
            return url;
        }
    }

}
