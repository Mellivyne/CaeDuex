package com.cheatprogram.capture;

import java.io.IOException;
import java.net.URI;

/** The address travels with the random capability; no central account or directory is needed. */
public record SharedCastInvite(String host, int port, String token, String pin) {
    public SharedCastInvite(String host,int port,String token){this(host,port,token,"");}
    public SharedCastInvite {
        pin=pin==null?"":pin;
        if(!pin.isEmpty()&&!pin.matches("[A-Za-z0-9_-]{43}"))throw new IllegalArgumentException("Invalid certificate pin");
        if (host == null || host.isBlank() || host.length() > 253 || port < 1 || port > 65535
                || token != null && !token.matches("[A-Za-z0-9_-]{22}\\.[A-Za-z0-9_-]{43}")) throw new IllegalArgumentException("Invalid invitation");
    }
    public static SharedCastInvite endpoint(String value) throws IOException { return parse(value, false); }
    public static SharedCastInvite parse(String value) throws IOException { return parse(value, true); }
    private static SharedCastInvite parse(String value, boolean requiredToken) throws IOException {
        try {
            String text = value.strip();
            URI uri = URI.create(text.contains("://") ? text : "cpcast://" + text);
            if (!"cpcast".equals(uri.getScheme()) && !"tls".equals(uri.getScheme())) throw new IllegalArgumentException();
            if (uri.getRawUserInfo() != null) throw new IllegalArgumentException();
            String query=uri.getRawQuery(),pin="";
            if(query!=null){if(!query.matches("pin=[A-Za-z0-9_-]{43}"))throw new IllegalArgumentException();pin=query.substring(4);}
            String path = uri.getRawPath(), token = path == null || path.isEmpty() || path.equals("/") ? null : path.substring(1);
            if (requiredToken && (token == null || uri.getRawFragment() == null) || !requiredToken && (token != null || uri.getRawFragment() != null)) throw new IllegalArgumentException();
            if (requiredToken) token += "." + uri.getRawFragment();
            return new SharedCastInvite(uri.getHost(), uri.getPort() == -1 ? 8443 : uri.getPort(), token,pin);
        } catch (RuntimeException invalid) { throw new IOException("ui.appcast.share.invalidInvite", invalid); }
    }
    public String code(String value) {
        if (!value.matches("[A-Za-z0-9_-]{22}\\.[A-Za-z0-9_-]{43}")) throw new IllegalArgumentException("Invalid capability");
        return "cpcast://" + (host.contains(":") && !host.startsWith("[") ? "[" + host + "]" : host) + ":" + port + "/" + value.substring(0,22) + (pin.isEmpty()?"":"?pin="+pin) + "#" + value.substring(23);
    }
}
