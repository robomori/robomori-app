package com.robomori.helper;

/** hev-socks5-tunnel, which carries each TCP connection in the VPN's packets over its own SOCKS5 connection. */
final class Tunnel {
    static { System.loadLibrary("robomori-vpn"); }

    private Tunnel() { }

    /** Carries the packets of {@code tunFd} as {@code config} says, until {@link #quit}. Only one runs at a time. */
    static native int run(String config, int tunFd);

    static native void quit();
}
