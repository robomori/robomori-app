package com.robomori.device;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.UiAutomation;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.os.Bundle;
import android.os.Looper;
import android.os.SystemClock;
import android.view.InputEvent;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeoutException;

/**
 * Lets the computer read and operate the device's screen for coding agents. It runs as Android's shell user through
 * app_process, like the screen mirror, so nothing is installed. It stays running between commands and answers on the
 * local socket {@value #SOCKET}: each request is a length-prefixed list of words separated by NUL, and each answer a
 * status byte (0 when it worked), a length and UTF-8 text, or JPEG bytes for a screenshot.
 *
 * The screen is read through the accessibility tree that UiAutomation exposes, and input goes through the same
 * connection, so both take milliseconds instead of the seconds that `uiautomator dump` and `input` cost. Only one
 * UiAutomation connection can exist on a device, so this one lets go after {@link #RELEASE_AFTER_MS} without
 * commands, leaving the device free for UI tests and `uiautomator`.
 */
public final class Agent {
    static final String SOCKET = "robomori-agent";
    private static final long RELEASE_AFTER_MS = 30_000;
    private static final long EXIT_AFTER_MS = 30 * 60_000;

    private final String version;
    private final Object lock = new Object();
    private UiAutomation automation;
    private volatile long lastRequest = System.currentTimeMillis();
    /** The screen whose refs commands take: the last one printed with refs. */
    private Screen shown;
    /** The screen last read, which the next diff goes from, and whether refs started counting again since. */
    private Screen read;
    private boolean renumbered;
    private int nextRef = 1;

    Agent(String version) { this.version = version; }

    public static void main(String[] args) throws Exception {
        LocalServerSocket server;
        try {
            server = new LocalServerSocket(SOCKET);
        } catch (IOException running) {
            System.err.println("Another agent is already running.");
            return;
        }
        // Android's accessibility client posts to the main looper, which app_process doesn't start.
        Thread looper = new Thread(() -> { Looper.prepareMainLooper(); Looper.loop(); }, "robomori-agent-looper");
        looper.setDaemon(true);
        looper.start();
        while (Looper.getMainLooper() == null) Thread.sleep(1);
        final Agent agent = new Agent(args.length > 0 ? args[0] : "");
        Thread watchdog = new Thread(() -> agent.watch(), "robomori-agent-idle");
        watchdog.setDaemon(true);
        watchdog.start();
        while (true) {
            final LocalSocket socket = server.accept();
            Thread client = new Thread(() -> agent.serve(socket), "robomori-agent-client");
            client.setDaemon(true);
            client.start();
        }
    }

    private void watch() {
        while (true) {
            try { Thread.sleep(5_000); } catch (InterruptedException e) { return; }
            long idle = System.currentTimeMillis() - lastRequest;
            if (idle > EXIT_AFTER_MS) System.exit(0);
            if (idle > RELEASE_AFTER_MS) synchronized (lock) { release(); }
        }
    }

    private void serve(LocalSocket socket) {
        try {
            DataInputStream in = new DataInputStream(socket.getInputStream());
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            while (true) {
                int length = in.readInt();
                if (length < 0 || length > 1 << 20) throw new IOException("Invalid request");
                byte[] request = new byte[length];
                in.readFully(request);
                String[] words = new String(request, StandardCharsets.UTF_8).split("\0", -1);
                lastRequest = System.currentTimeMillis();
                byte status = 0;
                byte[] answer;
                try {
                    synchronized (lock) { answer = handle(words); }
                } catch (Throwable failure) {
                    status = 1;
                    Throwable cause = failure instanceof InvocationTargetException ? failure.getCause() : failure;
                    String message = cause.getMessage();
                    answer = (message == null ? cause.toString() : message).getBytes(StandardCharsets.UTF_8);
                }
                lastRequest = System.currentTimeMillis();
                out.writeByte(status);
                out.writeInt(answer.length);
                out.write(answer);
                out.flush();
                if ("quit".equals(words[0])) System.exit(0);
            }
        } catch (EOFException closed) {
            // The computer finished.
        } catch (IOException failure) {
            // The computer went away.
        } finally {
            try { socket.close(); } catch (IOException ignored) { }
        }
    }

    private byte[] handle(String[] words) throws Exception {
        String command = words[0];
        switch (command) {
            case "version": return text(version);
            case "quit": return text("");
            case "ui":
                // Something the agent didn't do changed the screen, such as rotating it: wait for it as after an action.
                if (has(words, 1, "acted")) lastAction = SystemClock.uptimeMillis();
                return text(ui(has(words, 1, "all"), has(words, 1, "diff")));
            case "tap": return text(tap(arg(words, 1), has(words, 2, "long"), option(words, 2, "times", 1)));
            case "swipe": return text(gesture(words, false));
            case "drag": return text(gesture(words, true));
            case "zoom": return text(zoom(arg(words, 1), optional(words, 2)));
            case "scroll": return text(scroll(arg(words, 1), optional(words, 2), option(words, 3, "until")));
            case "type": return text(type(arg(words, 1), words.length > 2 ? words[2] : "", has(words, 3, "submit"), has(words, 3, "clear")));
            case "key": key(arg(words, 1), has(words, 2, "long")); return text("");
            case "wait": return text(waitFor(arg(words, 1), num(words, 2), has(words, 3, "gone")));
            case "screenshot": return screenshot(num(words, 1), num(words, 2));
            case "launch": return text(launch(arg(words, 1), has(words, 2, "restart"), has(words, 2, "clear")));
            case "close": return text(close(arg(words, 1)));
            case "apps": return text(apps());
            case "clipboard": return text(words.length > 1 ? setClipboard(words[1]) : clipboard());
            case "logs": return text(logs(arg(words, 1), num(words, 2), has(words, 3, "all")));
            default: throw new IllegalArgumentException("Unknown command: " + command);
        }
    }

    private static byte[] text(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    /** Whether [flag] is among the words from [from] on, after a command's own arguments, so text can't pose as one. */
    private static boolean has(String[] words, int from, String flag) {
        for (int i = from; i < words.length; i++) if (flag.equals(words[i])) return true;
        return false;
    }
    /** The value of [name]=value among the words from [from] on, or null. */
    private static String option(String[] words, int from, String name) {
        for (int i = from; i < words.length; i++) if (words[i].startsWith(name + "=")) return words[i].substring(name.length() + 1);
        return null;
    }
    private static int option(String[] words, int from, String name, int fallback) {
        String value = option(words, from, name);
        return value == null ? fallback : Integer.parseInt(value);
    }
    /** The argument at [index], or null when it's missing or empty. */
    private static String optional(String[] words, int index) {
        return words.length > index && !words[index].isEmpty() ? words[index] : null;
    }
    private static String arg(String[] words, int index) {
        if (words.length <= index) throw new IllegalArgumentException("Missing argument");
        return words[index];
    }
    private static int num(String[] words, int index) { return Integer.parseInt(arg(words, index)); }

    // Connection

    private UiAutomation automation() throws Exception {
        if (automation != null) return automation;
        Object connection = Class.forName("android.app.UiAutomationConnection").getDeclaredConstructor().newInstance();
        Class<?> connectionType = Class.forName("android.app.IUiAutomationConnection");
        UiAutomation created;
        try {
            created = UiAutomation.class.getDeclaredConstructor(Looper.class, connectionType)
                .newInstance(Looper.getMainLooper(), connection);
        } catch (NoSuchMethodException newerAndroid) {
            Class<?> context = Class.forName("android.content.Context");
            Object activityThread = Class.forName("android.app.ActivityThread").getMethod("systemMain").invoke(null);
            Object system = activityThread.getClass().getMethod("getSystemContext").invoke(activityThread);
            created = UiAutomation.class.getDeclaredConstructor(context, connectionType).newInstance(system, connection);
        }
        try {
            // Without this flag, TalkBack and other accessibility services pause while the agent is connected.
            UiAutomation.class.getDeclaredMethod("connect", int.class).invoke(created, 1 /* Don't suppress accessibility services */);
        } catch (NoSuchMethodException olderAndroid) {
            UiAutomation.class.getDeclaredMethod("connect").invoke(created);
        }
        AccessibilityServiceInfo info = created.getServiceInfo();
        // Every window, not only the focused one; views apps mark unimportant; and view IDs.
        info.flags |= 0x40 | 0x02 | 0x10;
        created.setServiceInfo(info);
        created.setOnAccessibilityEventListener(this::heard);
        automation = created;
        return created;
    }

    /** Toasts shown since the screen was last read, which aren't in any window accessibility lists. */
    private final List<String> toasts = new ArrayList<>();

    private void heard(AccessibilityEvent event) {
        CharSequence type = event.getClassName();
        if (event.getEventType() != 64 /* Notification state changed */ || type == null || !type.toString().contains("Toast")) return;
        StringBuilder text = new StringBuilder();
        for (CharSequence part : event.getText()) if (part != null) text.append(text.length() == 0 ? "" : " ").append(part);
        String toast = clean(text);
        if (!toast.isEmpty()) synchronized (toasts) { toasts.add("toast \"" + clip(toast) + "\""); }
    }

    /** The toasts heard since the last call, one line each. */
    private String takeToasts() {
        synchronized (toasts) {
            String lines = joined(toasts);
            toasts.clear();
            return lines;
        }
    }

    private void release() {
        if (automation == null) return;
        try { UiAutomation.class.getDeclaredMethod("disconnect").invoke(automation); } catch (Exception ignored) { }
        automation = null;
    }

    // Reading the screen

    /** One thing on the screen: something to act on, which gets a ref, or text to read. */
    static final class Element {
        int ref;
        final String role, label, state, key;
        /** The view's ID, which names it when it has no text. */
        String id = "";
        /**
         * Which node this is, from Android's own identity for it: its window, view and virtual view, as for a WebView's
         * or Compose's elements. It stays the same while the element exists, whatever its text, so its ref does too.
         */
        String node = "";
        /** What a text field holds, when it also has a name to go by, such as its label. */
        String value = "";
        /** Whether this text is a heading, such as a section's title, which shows as "# Title". */
        boolean heading;
        final Rect bounds;
        final boolean scroll;

        Element(String role, String label, String state, Rect bounds, boolean scroll, String key) {
            this.role = role; this.label = label; this.state = state; this.bounds = bounds; this.scroll = scroll; this.key = key;
        }

        boolean actionable() { return role != null; }

        /** Whether this is [other] as it was, moved by no more than a few pixels each way. */
        boolean barelyMovedFrom(Element other) {
            if (other == null || !role.equals(other.role) || !label.equals(other.label) || !state.equals(other.state)
                || !id.equals(other.id) || !value.equals(other.value)) return false;
            int most = 48;
            return Math.abs(bounds.left - other.bounds.left) <= most && Math.abs(bounds.top - other.bounds.top) <= most
                && Math.abs(bounds.right - other.bounds.right) <= most && Math.abs(bounds.bottom - other.bounds.bottom) <= most;
        }

        String line() {
            if (!actionable()) return heading ? "# " + label : label;
            StringBuilder line = new StringBuilder("@").append(ref);
            if (!role.isEmpty()) line.append(' ').append(role);
            if (!label.isEmpty()) line.append(" \"").append(label).append('"');
            else if (!id.isEmpty()) line.append(" id:").append(id);
            if (!value.isEmpty()) line.append(" = \"").append(value).append('"');
            if (!state.isEmpty()) line.append(' ').append(state);
            if (scroll) line.append(" [").append(bounds.left).append(',').append(bounds.top).append(',')
                .append(bounds.right).append(',').append(bounds.bottom).append(']');
            else line.append(" (").append(bounds.centerX()).append(',').append(bounds.centerY()).append(')');
            return line.toString();
        }
    }

    static final class Screen {
        final String header;
        final List<Element> elements;
        final Rect size;
        /** Whether a progress indicator shows, such as a spinner while results load. */
        final boolean loading;
        /** Whether no app shows anything yet, as between screens, while an app starts or a web page first draws. */
        final boolean blank;
        Screen(String header, List<Element> elements, Rect size, boolean loading, boolean blank) {
            this.header = header; this.elements = elements; this.size = size; this.loading = loading; this.blank = blank;
        }

        /** Whether something sits off the screen, as while a window scales or slides in, so its place isn't final. */
        boolean moving() {
            for (Element element : elements) {
                int x = element.bounds.centerX(), y = element.bounds.centerY();
                if (x < 0 || y < 0 || x > size.width() || y > size.height()) return true;
            }
            return false;
        }

        List<String> lines() {
            List<String> lines = new ArrayList<>();
            for (Element element : elements) lines.add(element.line());
            return lines;
        }

        String signature() {
            StringBuilder text = new StringBuilder(header);
            for (Element element : elements) text.append('\n').append(element.key).append(element.bounds.left).append(',')
                .append(element.bounds.top).append(',').append(element.bounds.right).append(',').append(element.bounds.bottom).append(element.state);
            return text.toString();
        }

        String text() {
            StringBuilder text = new StringBuilder(header);
            for (Element element : elements) text.append('\n').append(element.line());
            return text.toString();
        }
    }

    /**
     * Drops the accessibility cache, which windows that move without telling accessibility, as in transitions, leave
     * out of date. The next read asks the apps again.
     */
    private void forget() throws Exception {
        UiAutomation automation = automation();
        try {
            UiAutomation.class.getMethod("clearCache").invoke(automation);
            return;
        } catch (NoSuchMethodException olderAndroid) { /* Android 13 and earlier */ }
        try {
            Class<?> client = Class.forName("android.view.accessibility.AccessibilityInteractionClient");
            Object instance = client.getMethod("getInstance").invoke(null);
            client.getMethod("clearCache").invoke(instance);
        } catch (ReflectiveOperationException unavailable) { /* Read what the cache holds. */ }
    }

    private void settle(long quietMs, long maxMs) throws Exception {
        try { automation().waitForIdle(quietMs, maxMs); } catch (TimeoutException busy) { /* Still animating; read it as it is. */ }
    }

    /**
     * Reads the screen once it has stopped changing. Window transitions move things in steps without telling
     * accessibility, so within a second of an action the screen must also hold still, for [stillFor]. A screen that
     * shows something loading then is waited on for up to 5 seconds, and its header says "loading" if it still is. One
     * where no app shows anything yet, as between screens, is waited on for up to 3.
     * Otherwise gives up after a few seconds of animation.
     */
    private Screen settled(boolean all) throws Exception {
        long start = SystemClock.uptimeMillis();
        boolean afterAction = start - lastAction < Math.max(1_000, stillFor);
        long still = afterAction ? stillFor : 0;
        settle(100, 1_000);
        forget();
        Screen screen = read(all);
        long changed = SystemClock.uptimeMillis();
        while (SystemClock.uptimeMillis() - start < (afterAction && screen.loading ? 5_000 : 3_000)
            && (SystemClock.uptimeMillis() - changed < still || (afterAction && (screen.loading || screen.blank)) || screen.moving())) {
            Thread.sleep(50);
            forget();
            Screen next = read(all);
            if (!next.signature().equals(screen.signature())) changed = SystemClock.uptimeMillis();
            screen = next;
        }
        return screen;
    }

    private String ui(boolean all, boolean diff) throws Exception {
        Screen previous = read;
        boolean wasRenumbered = renumbered;
        Screen screen = settled(all);
        // Going back fades the old screen out while it's still listed, so right after an action, the same screen as
        // before may only mean the new one hasn't shown yet.
        long sinceAction = SystemClock.uptimeMillis() - lastAction;
        if (previous != null && lastAction > 0 && sinceAction < 1_000 && previous.signature().equals(screen.signature())) {
            long deadline = lastAction + 1_500;
            while (SystemClock.uptimeMillis() < deadline && previous.signature().equals(screen.signature())) {
                Thread.sleep(100);
                screen = settled(all);
            }
        }
        boolean fresh = number(screen, true) || wasRenumbered || previous == null;
        String text = !diff || fresh ? screen.text() : changes(previous, screen);
        // An app that crashed after an action leaves the screen; say why, as the screen alone doesn't.
        boolean justActed = lastAction > 0 && SystemClock.uptimeMillis() - lastAction < 10_000;
        String crash = justActed ? crashSince(Math.max(lastAction, crashesShownUntil)) : "";
        if (justActed) crashesShownUntil = SystemClock.uptimeMillis();
        if (!crash.isEmpty()) text = text + "\n" + crash;
        String toasts = takeToasts();
        if (toasts.isEmpty()) return text;
        return text.equals("No change.") ? toasts : text + "\n" + toasts;
    }

    /**
     * Gives [screen] refs, keeping those of elements already shown, and makes it the screen refs go by. With [asRead],
     * the next diff also goes from it; without, as when a command prints one element of it, the diff still goes from
     * the screen last read. True when it's mostly new, which starts counting again so refs stay short.
     */
    boolean number(Screen screen, boolean asRead) {
        Map<String, Integer> byNode = new HashMap<>(), byKey = new HashMap<>();
        Map<String, String> labels = new HashMap<>();
        if (shown != null) for (Element element : shown.elements) if (element.actionable()) {
            if (!element.node.isEmpty()) { byNode.put(element.node, element.ref); labels.put(element.node, element.label); }
            byKey.put(element.key, element.ref);
        }
        Map<String, String> nodesNow = new HashMap<>();
        for (Element element : screen.elements) if (element.actionable() && !element.node.isEmpty()) nodesNow.put(element.node, element.label);
        // A ref whose element is still on the screen as it was stays with it, so another element that now looks like it,
        // as when a button's text changes and the next one becomes the first with the old text, can't take it over.
        Set<Integer> held = new HashSet<>();
        if (shown != null) for (Element element : shown.elements) {
            if (element.actionable() && element.label.equals(nodesNow.get(element.node))) held.add(element.ref);
        }
        Map<Element, Integer> kept = new HashMap<>();
        int actionable = 0;
        for (Element element : screen.elements) {
            if (!element.actionable()) continue;
            actionable++;
            // The same node showing something else, as a list row reused for the next item, is something else to act on.
            boolean same = element.role.equals("field") || element.label.equals(labels.get(element.node));
            Integer ref = same ? byNode.get(element.node) : null;
            if (ref == null) {
                Integer alike = byKey.get(element.key);
                if (alike != null && !held.contains(alike)) ref = alike;
            }
            if (ref != null && !kept.containsValue(ref)) kept.put(element, ref);
        }
        boolean fresh = shown == null || kept.size() * 2 < Math.max(actionable, byKey.size());
        if (fresh) { kept.clear(); nextRef = 1; renumbered = true; }
        for (Element element : screen.elements) {
            if (!element.actionable()) continue;
            Integer ref = kept.get(element);
            element.ref = ref != null ? ref : nextRef++;
        }
        shown = screen;
        if (asRead) { read = screen; renumbered = false; }
        return fresh;
    }

    /**
     * What changed between two screens: "+ " before each line that's new or different, and "- " before what's gone. A
     * ref still on the screen shows only its new line, and a ref that's gone only its label. Something that only moved
     * a little, as when a list shifts by a few pixels, isn't a change.
     */
    static String changes(Screen previous, Screen screen) {
        Set<String> is = new HashSet<>(screen.lines()), was = new HashSet<>(previous.lines());
        Set<Integer> refs = new HashSet<>();
        for (Element element : screen.elements) if (element.actionable()) refs.add(element.ref);
        Map<Integer, Element> before = new HashMap<>();
        for (Element element : previous.elements) if (element.actionable()) before.put(element.ref, element);
        StringBuilder text = new StringBuilder();
        if (!previous.header.equals(screen.header)) text.append(screen.header).append('\n');
        for (Element element : previous.elements) {
            if (is.contains(element.line()) || (element.actionable() && refs.contains(element.ref))) continue;
            text.append("- ").append(element.actionable() ? "@" + element.ref + (element.label.isEmpty() ? "" : " \"" + element.label + "\"")
                : element.label).append('\n');
        }
        for (Element element : screen.elements) {
            if (was.contains(element.line()) || (element.actionable() && element.barelyMovedFrom(before.get(element.ref)))) continue;
            text.append("+ ").append(element.line()).append('\n');
        }
        return text.length() == 0 ? "No change." : text.substring(0, text.length() - 1);
    }

    private Screen read(boolean all) throws Exception {
        UiAutomation automation = automation();
        List<AccessibilityWindowInfo> windows = new ArrayList<>(automation.getWindows());
        Collections.sort(windows, (a, b) -> b.getLayer() - a.getLayer());
        Rect size = sizeOf(windows);
        List<Element> elements = new ArrayList<>();
        List<Rect> above = new ArrayList<>();
        Map<String, Integer> seen = new HashMap<>();
        loading = false;
        String app = null;
        AccessibilityNodeInfo appWindow = null;
        // The system's own package, such as the notification shade's, when no app shows.
        String system = null;
        boolean keyboard = false;
        for (AccessibilityWindowInfo window : windows) {
            int type = window.getType();
            Rect bounds = new Rect();
            window.getBoundsInScreen(bounds);
            boolean skip = false;
            if (type == 2) { keyboard = true; skip = !all; } // The keyboard: type text instead of tapping keys.
            else if (type == 4 || type == 5) skip = true; // Accessibility overlays and the split-screen divider.
            else if (type == 3 && !all) skip = isBar(bounds, size); // The status and navigation bars.
            if (!skip) {
                AccessibilityNodeInfo root = window.getRoot();
                if (root != null) {
                    if (app == null && type == 1 && root.getPackageName() != null) app = root.getPackageName().toString();
                    if (appWindow == null && type == 1) appWindow = root;
                    if (system == null && root.getPackageName() != null) system = root.getPackageName().toString();
                    int from = elements.size();
                    walk(root, null, bounds, above, elements, seen);
                    // Top to bottom, as it reads: apps list some things, such as a top bar, after what's under them.
                    Collections.sort(elements.subList(from, elements.size()), (a, b) ->
                        a.bounds.top != b.bounds.top ? a.bounds.top - b.bounds.top : a.bounds.left - b.bounds.left);
                }
            }
            // Whatever this window covers can't be touched underneath it.
            if (type != 4) above.add(bounds);
        }
        if (windows.isEmpty()) {
            AccessibilityNodeInfo root = automation.getRootInActiveWindow();
            if (root != null) {
                appWindow = root;
                Rect bounds = new Rect();
                root.getBoundsInScreen(bounds);
                size = bounds;
                if (root.getPackageName() != null) app = root.getPackageName().toString();
                walk(root, null, bounds, above, elements, seen);
            }
        }
        // Unlabeled things on top of labeled ones, such as a widget's touch targets, only repeat them.
        List<Element> unique = new ArrayList<>();
        for (Element element : elements) {
            if (element.actionable() && element.label.isEmpty() && !element.scroll && overlapsLabeled(element, elements)) continue;
            unique.add(element);
        }
        elements = unique;
        if (app == null) app = system;
        String header = (app == null ? "?" : app) + " " + size.width() + "x" + size.height() + (keyboard ? " keyboard" : "")
            + (loading ? " loading" : "") + slowAnimations();
        return new Screen(header, elements, size, loading, appWindow == null || elements.isEmpty());
    }

    /**
     * The screen's size: the display's, as it's turned now, or else what its windows cover together, which is less
     * while a dialog's window is the only one accessibility lists.
     */
    private static Rect sizeOf(List<AccessibilityWindowInfo> windows) {
        Rect size = new Rect();
        try {
            Class<?> displays = Class.forName("android.hardware.display.DisplayManagerGlobal");
            Object info = displays.getMethod("getDisplayInfo", int.class).invoke(displays.getMethod("getInstance").invoke(null), 0);
            size.right = info.getClass().getField("logicalWidth").getInt(info);
            size.bottom = info.getClass().getField("logicalHeight").getInt(info);
            if (size.width() > 0 && size.height() > 0) return size;
        } catch (ReflectiveOperationException | RuntimeException unavailable) { /* Measure the windows instead. */ }
        for (AccessibilityWindowInfo window : windows) {
            Rect bounds = new Rect();
            window.getBoundsInScreen(bounds);
            size.right = Math.max(size.right, bounds.right);
            size.bottom = Math.max(size.bottom, bounds.bottom);
        }
        return size;
    }

    private Rect screenSize() throws Exception {
        Rect size = sizeOf(automation().getWindows());
        if (size.width() > 0) return size;
        AccessibilityNodeInfo root = automation().getRootInActiveWindow();
        if (root != null) root.getBoundsInScreen(size);
        return size;
    }

    private static boolean overlapsLabeled(Element element, List<Element> elements) {
        for (Element other : elements) {
            if (other == element || other.label.isEmpty()) continue;
            if (Math.abs(other.bounds.centerX() - element.bounds.centerX()) <= 16
                && Math.abs(other.bounds.centerY() - element.bounds.centerY()) <= 16) return true;
        }
        return false;
    }

    private static boolean isBar(Rect window, Rect screen) {
        // Against the longer side, so the status bar of a phone turned sideways still counts as thin.
        int longer = Math.max(screen.width(), screen.height());
        boolean thin = window.height() < longer / 8 && (window.top <= 0 || window.bottom >= screen.bottom);
        boolean narrow = window.width() < longer / 8 && (window.left <= 0 || window.right >= screen.right);
        return thin || narrow;
    }

    /**
     * Adds [node] and what's inside it. Something to act on takes the text inside it as its label, the way a screen
     * reader announces a list item as one, so [label] collects that text when it isn't null.
     */
    private void walk(AccessibilityNodeInfo node, StringBuilder label, Rect window, List<Rect> above, List<Element> out,
                      Map<String, Integer> seen) {
        if (node == null || !node.isVisibleToUser()) return;
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        if (!bounds.intersect(window)) return;
        String own = labelOf(node);
        boolean editable = node.isEditable();
        boolean actionable = node.isClickable() || node.isLongClickable() || node.isCheckable() || editable;
        boolean scrollable = node.isScrollable() && !editable;
        boolean hidden = covered(bounds, above);
        if (!hidden && (isProgress(node) || isLoadingText(own))) loading = true;
        if (actionable || scrollable) {
            int at = out.size();
            // A list's rows stay rows: gathering their text would make one line of everything in it. A field's own text is
            // what it holds; the text inside it, such as its label, names it.
            StringBuilder inner = (actionable && !scrollable && own.isEmpty()) || editable ? new StringBuilder() : null;
            for (int i = 0; i < node.getChildCount(); i++) walk(node.getChild(i), inner, window, above, out, seen);
            String says = own.toLowerCase(Locale.ROOT);
            boolean list = scrollable && !actionable;
            boolean labelRepeatsRows = false;
            if (!own.isEmpty()) for (int i = out.size() - 1; i >= at; i--) {
                Element inside = out.get(i);
                if (inside.actionable() || !says.contains(inside.label.toLowerCase(Locale.ROOT))) continue;
                // A list's rows stay; a label the browser made of all of them goes instead.
                if (list) labelRepeatsRows = true;
                // Text inside something that already says it, as a web link's text inside the link, only repeats it.
                else out.remove(i);
            }
            if (hidden) return;
            String text = clip(inner != null && !editable ? inner.toString() : labelRepeatsRows ? "" : own);
            String value = "";
            if (editable) {
                String name = clip(inner.toString());
                if (name.isEmpty()) name = clip(fieldName(node));
                if (!name.isEmpty() && !text.isEmpty() && !name.equals(text) && !showsHint(node)) { value = text; text = name; }
                else if (!name.isEmpty() && (text.isEmpty() || showsHint(node))) text = name.equals(text) || text.isEmpty() ? name : name + ", " + text;
            }
            // A sliver of a row cut off at the edge of a list, with none of its text showing yet.
            if (text.isEmpty() && idOf(node).isEmpty() && Math.min(bounds.width(), bounds.height()) < 32) return;
            String role = scrollable && !actionable ? "list" : roleOf(node);
            String state = stateOf(node, scrollable);
            // A field keeps its ref while its text changes.
            String identity = role + '\u0001' + (editable ? "" : text);
            Integer count = seen.get(identity);
            seen.put(identity, count == null ? 1 : count + 1);
            Element element = new Element(role, text, state, bounds, scrollable && !actionable,
                identity + '\u0001' + idOf(node) + '\u0001' + (count == null ? 0 : count));
            element.id = idOf(node);
            element.value = value;
            element.node = node.getWindowId() + "/" + node.hashCode();
            out.add(at, element);
            return;
        }
        if (!own.isEmpty()) {
            if (label != null) {
                if (label.length() > 0) label.append(", ");
                label.append(own);
            } else if (!hidden) {
                Element text = new Element(null, clip(own), "", bounds, false, own);
                try { text.heading = node.isHeading(); } catch (NoSuchMethodError olderAndroid) { }
                out.add(text);
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) walk(node.getChild(i), label, window, above, out, seen);
    }

    private static String quoted(String label) { return label.isEmpty() ? "unlabeled" : "\"" + label + "\""; }

    /** When a list on [screen] has more that way, how to look there for [text]. */
    static String scrollHint(Screen screen, String text) {
        // The keyboard covers the bottom of the screen, and what's under it can't be tapped.
        if (screen.header.contains(" keyboard")) return " The keyboard is up and may cover it: robomori input press back hides it.";
        // Further down first, where most of a list is, then sideways, and back up last.
        String[][] ways = {{"↓", "down"}, {"→", "right"}, {"↑", "up"}, {"←", "left"}};
        for (String[] way : ways) for (Element element : screen.elements) if (element.scroll && element.state.contains(way[0]))
            return " A list has more " + way[1] + ": robomori input scroll " + way[1] + " --until \"" + text + "\"";
        return "";
    }

    private String animationScale = "1";
    private long animationScaleRead = -60_000;

    /**
     * " animations 10x" when the device plays animations slower than normal, which makes everything take longer to settle
     * and screens read mid-transition. Read again every 10 seconds.
     */
    private String slowAnimations() {
        long now = SystemClock.uptimeMillis();
        if (now - animationScaleRead > 10_000) {
            animationScaleRead = now;
            try { animationScale = shell("settings", "get", "global", "animator_duration_scale").trim(); } catch (Exception unreadable) { }
        }
        try {
            float scale = animationScale.equals("null") ? 1f : Float.parseFloat(animationScale);
            if (scale > 1f) return " animations " + (scale == Math.floor(scale) ? String.valueOf((int) scale) : String.valueOf(scale)) + "x";
        } catch (NumberFormatException unknown) { }
        return "";
    }

    /** Set by [read] when it comes across a progress indicator. */
    private boolean loading;

    /** A spinner or progress bar, which Compose's progress indicators also report themselves as. */
    private static boolean isProgress(AccessibilityNodeInfo node) {
        CharSequence className = node.getClassName();
        return className != null && className.toString().endsWith("ProgressBar");
    }

    /** Text a web page shows while it loads, where it has no progress indicator accessibility knows of. */
    private static boolean isLoadingText(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        return lower.equals("loading") || lower.equals("loading…") || lower.equals("loading...") || lower.equals("loader")
            || lower.equals("please wait") || lower.equals("please wait…") || lower.equals("please wait...");
    }

    private static boolean covered(Rect bounds, List<Rect> above) {
        for (Rect rect : above) if (rect.contains(bounds.centerX(), bounds.centerY())) return true;
        return false;
    }

    private static boolean showsHint(AccessibilityNodeInfo node) {
        try { return node.isShowingHintText(); } catch (NoSuchMethodError olderAndroid) { return false; }
    }

    /** What a text field is for, from its hint or description, which its text replaces once it has some. */
    private static String fieldName(AccessibilityNodeInfo node) {
        String name = "";
        try { name = clean(node.getHintText()); } catch (NoSuchMethodError olderAndroid) { }
        return name.isEmpty() ? clean(node.getContentDescription()) : name;
    }

    private static String labelOf(AccessibilityNodeInfo node) {
        CharSequence text = node.getText();
        boolean hint = false;
        try { hint = node.isShowingHintText(); } catch (NoSuchMethodError olderAndroid) { }
        if (hint) text = null;
        if (node.isPassword() && text != null && text.length() > 0) text = "•••";
        String value = clean(text);
        if (value.isEmpty()) value = clean(node.getContentDescription());
        if (value.isEmpty() && node.isEditable()) {
            try { value = clean(node.getHintText()); } catch (NoSuchMethodError olderAndroid) { }
        }
        return value;
    }

    private static String clean(CharSequence text) {
        if (text == null) return "";
        // Icon fonts draw their icons with private-use characters, which say nothing as text.
        return text.toString().replaceAll("[\\uE000-\\uF8FF]", "").replaceAll("\\s+", " ").trim();
    }

    /** One per line, as String.join does from Android 8 on. */
    private static String joined(Iterable<String> lines) {
        StringBuilder text = new StringBuilder();
        for (String line : lines) text.append(text.length() == 0 ? "" : "\n").append(line);
        return text.toString();
    }

    private static String clip(String text) { return text.length() > 100 ? text.substring(0, 99) + "…" : text; }

    private static String idOf(AccessibilityNodeInfo node) {
        String id = node.getViewIdResourceName();
        if (id == null) return "";
        int slash = id.indexOf(":id/");
        return slash >= 0 ? id.substring(slash + 4) : id;
    }

    private static String roleOf(AccessibilityNodeInfo node) {
        CharSequence className = node.getClassName();
        String name = className == null ? "" : className.toString();
        name = name.substring(name.lastIndexOf('.') + 1);
        if (node.isEditable() || name.contains("EditText")) return "field";
        if (name.contains("Switch")) return "switch";
        if (name.contains("CheckBox")) return "checkbox";
        if (name.contains("RadioButton")) return "radio";
        if (name.contains("SeekBar") || name.contains("Slider")) return "slider";
        if (name.contains("Spinner")) return "dropdown";
        if (name.contains("Button")) return "button";
        if (name.contains("Image")) return "image";
        if (name.equals("TabView") || name.equals("Tab")) return "tab";
        if (node.isCheckable()) {
            // Compose reports every element as a plain View, and says what a checkable one is through the state it
            // announces instead: selected for a radio button, on or off for a switch, checked for a checkbox.
            return checkableRole(stateDescriptionOf(node));
        }
        return "";
    }

    /**
     * The role of something that can be checked, from the state it announces: Compose's words for a radio button's
     * "Selected" and "Not selected" and a switch's "On" and "Off", in each of its languages, or else a checkbox.
     */
    static String checkableRole(String stateDescription) {
        String state = stateDescription.trim().toLowerCase(Locale.ROOT);
        if (RADIO_STATES.contains(state)) return "radio";
        if (SWITCH_STATES.contains(state)) return "switch";
        return "checkbox";
    }

    private static final Set<String> RADIO_STATES = new HashSet<>(Arrays.asList("atlasīts", "ausgewählt", "ayikhethiwe", "chưa chọn", "dipilih", "ei valittu", "ekki valið", "elemento selezionato", "gekies", "geselecteerd", "hautatu gabe", "hautatuta", "hindi napili", "hujachagua", "ikke valgt", "inte vald", "izabrano", "izbrano", "kijelölve", "napili", "nav atlasīts", "nepasirinkta", "neselectat", "nevybrané", "nevybráno", "ni izbrano", "nicht ausgewählt", "nie gekies nie", "nie wybrano", "niet geselecteerd", "nije izabrano", "nije odabrano", "nincs kijelölve", "no seleccionado", "no seleccionat", "non seleccionado", "non sélectionné", "not selected", "nuk është zgjedhur", "não selecionado", "odabrano", "okukhethiwe", "opzione non selezionata", "pasirinkta", "pole valitud", "seleccionado", "seleccionat", "selecionado", "selectat", "selected", "seçili", "seçili değil", "seçilib", "seçilməyib", "sin seleccionar", "sélectionné", "tanlangan", "tanlanmagan", "tidak dipilih", "umechagua", "valgt", "valittu", "valitud", "valið", "valt", "vybrané", "vybráno", "wybrano", "zgjedhur", "đã chọn", "δεν έχει επιλεχθεί", "επιλεγμένο", "вибрано", "выбрана", "выбрано", "изабрано", "избрано", "не вибрано", "не выбрана", "не выбрано", "не е избрано", "није изабрано", "сонгоогүй", "сонгосон", "тандалган жок", "тандалды", "таңдалды", "таңдалмады", "ընտրված է", "ընտրված չէ", "לא נבחר", "נבחר", "انتخاب شد", "انتخاب‌نشده", "غير محدّد", "غیر منتخب کردہ", "محدّد", "منتخب کردہ", "चयन गरिएको छ", "चयन गरिएको छैन", "चुना गया", "नहीं चुना गया", "निवडला", "निवडला नाही", "বাছনি কৰা হৈছে", "বাছনি কৰা হোৱা নাই", "বেছে নেওয়া হয়নি", "বেছে নেওয়া হয়েছে", "ਚੁਣੀ ਗਈ", "ਚੁਣੀ ਨਹੀਂ ਗਈ", "પસંદગી કરી", "પસંદગી કરી નથી", "ଚୟନ କରାଯାଇନାହିଁ", "ଚୟନିତ", "தேர்ந்தெடுக்கப்படவில்லை", "தேர்ந்தெடுக்கப்பட்டுள்ளது", "ఎంచుకోబడలేదు", "ఎంచుకోబడింది", "ಆಯ್ಕೆ ಮಾಡಿಲ್ಲ", "ಆಯ್ಕೆಮಾಡಲಾಗಿದೆ", "തിരഞ്ഞെടുത്തില്ല", "തിരഞ്ഞെടുത്തു", "තෝරන ලදි", "තෝරා නැත", "เลือกไว้", "ไม่ได้เลือก", "ບໍ່ໄດ້ເລືອກ", "ເລືອກແລ້ວ", "ရွေးထားသည်", "ရွေးမထားပါ", "არ არის არჩეული", "არჩეული", "ተመርጧል", "ያልተመረጡ", "បានជ្រើសរើស", "មិនបាន​ជ្រើសរើស", "‎‏‎‎‎‎‎‏‎‏‏‏‎‎‎‎‎‎‏‎‎‏‎‎‎‎‏‏‏‎‏‎‏‏‎‏‎‏‎‏‏‏‏‎‎‎‏‎‎‏‏‏‎‎‎‎‏‏‎‎‎‏‏‏‏‎‎‎‏‎‎‏‎‎‎‎‏‏‎‏‎‎‏‏‏‏‎selected‎‏‎‎‏‎", "‎‏‎‎‎‎‎‏‎‏‏‏‎‎‎‎‎‎‏‎‎‏‎‎‎‎‏‏‏‏‏‏‏‏‎‏‏‎‏‏‏‎‏‏‏‏‎‏‎‎‎‏‎‏‏‏‎‏‎‏‎‎‎‎‏‎‎‎‎‏‏‎‎‏‎‏‏‎‏‎‏‏‎‏‏‎‎‏‎‎‎‎‎‏‏‏‎not selected‎‏‎‎‏‎", "已选择", "已選取", "揀咗", "未揀", "未选择", "未選取", "未選択", "選択済み", "선택되지 않음", "선택됨"));
    private static final Set<String> SWITCH_STATES = new HashSet<>(Arrays.asList("aan", "activado", "activat", "activé", "af", "aktibatuta", "aktif", "aktiv", "an", "ativado", "aus", "av", "açık", "be", "deaktiv", "desactivado", "desactivat", "desaktibatuta", "desativado", "dezactivat", "désactivé", "fra", "hidup", "ieslēgts", "imewashwa", "imezimwa", "isključeno", "ivaliwe", "ivuliwe", "izklopljeno", "izslēgts", "išjungta", "joaktiv", "kapalı", "ki", "kveikt", "mati", "naka-off", "naka-on", "nonaktif", "off", "on", "oʻchiq", "pois päältä", "päällä", "på", "sees", "slökkt", "til", "uit", "uključeno", "vklopljeno", "vypnuto", "vypnuté", "väljas", "wyłączono", "włączono", "yoniq", "zapnuto", "zapnuté", "đang bật", "đang tắt", "įjungta", "ανενεργός", "ενεργός", "асаалттай", "вимкнено", "вклучено", "включено", "выключана", "изключено", "исклучено", "искључено", "күйүк", "отключено", "увімкнено", "уключана", "укључено", "унтраалттай", "қосулы", "өчүк", "өшірулі", "անջատված է", "միացված է", "מצב מושבת", "מצב פעיל", "آف ہے", "آن ہے", "خاموش", "روشن", "غير مفعَّل", "مفعَّل", "अन छ", "अफ छ", "चालू है", "बंद आहे", "बंद है", "सुरू आहे", "অন আছে", "অফ আছে", "চালু আছে", "বন্ধ আছে", "ਚਾਲੂ ਹੈ", "ਬੰਦ ਹੈ", "ચાલુ છે", "બંધ છે", "ଚାଲୁ ଅଛି", "ବନ୍ଦ ଅଛି", "ஆஃப்", "ஆன்", "ఆన్‌లో ఉంది", "ఆఫ్‌లో ఉంది", "ಆನ್ ಆಗಿದೆ", "ಆಫ್ ಆಗಿದೆ", "ഓണാണ്", "ഓഫാണ്", "ක්‍රියාත්මකයි", "ක්‍රියාවිරහිතයි", "ปิดอยู่", "เปิดอยู่", "ປິດຢູ່", "ເປີດຢູ່", "ပိတ်", "ဖွင့်", "გამორთული", "ჩართული", "በርቷል", "ጠፍቷል", "បិទ", "បើក", "‎‏‎‎‎‎‎‏‎‏‏‏‎‎‎‎‎‎‏‎‎‏‎‎‎‎‏‏‏‏‏‎‏‏‎‏‎‎‏‎‏‏‎‎‎‎‏‎‏‎‏‏‏‎‏‏‏‏‏‏‎‎‎‎‏‎‏‎‏‏‏‏‏‎‏‏‏‏‎‎‏‎‏‎‎‎‏‎‎‏‏‏‎‎‎on‎‏‎‎‏‎", "‎‏‎‎‎‎‎‏‎‏‏‏‎‎‎‎‎‎‏‎‎‏‎‎‎‎‏‏‏‏‏‏‏‏‎‏‎‎‎‎‏‏‏‏‏‎‎‎‏‏‎‎‏‎‏‏‎‏‏‏‎‏‏‏‎‏‎‏‎‏‏‏‎‎‏‎‏‎‏‎‎‎‏‏‏‎‏‏‏‎‎‏‏‎‏‏‎off‎‏‎‎‏‎", "已关闭", "已开启", "已開啟", "已關閉", "閂", "開", "사용", "사용 안함"));

    /** What a screen reader says of the element's state, such as "Selected", from Android 11 on. */
    private static String stateDescriptionOf(AccessibilityNodeInfo node) {
        try {
            Object state = AccessibilityNodeInfo.class.getMethod("getStateDescription").invoke(node);
            return state == null ? "" : state.toString();
        } catch (ReflectiveOperationException olderAndroid) { return ""; }
    }

    private static String stateOf(AccessibilityNodeInfo node, boolean scrollable) {
        StringBuilder state = new StringBuilder();
        if (node.isCheckable()) state.append(node.isChecked() ? " on" : " off");
        if (node.isSelected()) state.append(" selected");
        if (node.isFocused() && node.isEditable()) state.append(" focused");
        if (!node.isEnabled()) state.append(" disabled");
        if (scrollable) {
            Set<Integer> actions = new HashSet<>();
            for (AccessibilityNodeInfo.AccessibilityAction action : node.getActionList()) actions.add(action.getId());
            // The directional actions say which way there's more; older lists only say forward or back.
            String directions = (actions.contains(0x01020038) ? "↑" : "") + (actions.contains(0x0102003a) ? "↓" : "")
                + (actions.contains(0x01020039) ? "←" : "") + (actions.contains(0x0102003b) ? "→" : "");
            if (directions.isEmpty()) directions = (actions.contains(8192) ? "↑" : "") + (actions.contains(4096) ? "↓" : "");
            if (!directions.isEmpty()) state.append(" more ").append(directions);
        }
        return state.length() == 0 ? "" : state.substring(1);
    }

    // Finding what a command names

    /** [target] as an @ref from the screen last shown, x,y, or text, an ID or a description on the screen now. */
    private Element find(String target) throws Exception {
        String trimmed = target.trim();
        // Only @ marks a ref, so a bare number is text, such as a calculator's 7 or a date picker's 15.
        if (trimmed.matches("@\\d+")) {
            int ref = Integer.parseInt(trimmed.substring(1));
            Element known = null;
            if (shown != null) for (Element element : shown.elements) if (element.ref == ref && element.actionable()) known = element;
            if (known == null) throw new IllegalArgumentException("No @" + ref + " on the screen last shown. Read the screen again.");
            // Where it is now: a scroll or an animation since the screen was read may have moved it.
            Screen screen = settled(false);
            Element now = null;
            for (Element element : screen.elements) if (!known.node.isEmpty() && element.node.equals(known.node)) now = element;
            if (now == null) for (Element element : screen.elements) if (element.key.equals(known.key)) now = element;
            if (now == null) throw new IllegalArgumentException("@" + ref + " is no longer on the screen. Read it again.");
            // The same element showing something else, such as a list row reused for another item: acting on it may
            // not do what was meant when it was read. Its new text is then what refs go by, so trying again acts.
            if (!now.label.equals(known.label) && !now.role.equals("field")) {
                number(screen, false);
                throw new IllegalArgumentException("@" + ref + " is now " + quoted(now.label) + ", was " + quoted(known.label)
                    + ". Run the command again to act on it as it is now, or read the screen again.");
            }
            now.ref = ref;
            return now;
        }
        if (trimmed.matches("\\d+\\s*,\\s*\\d+")) {
            String[] parts = trimmed.split("\\s*,\\s*");
            int x = Integer.parseInt(parts[0]), y = Integer.parseInt(parts[1]);
            Rect size = screenSize();
            if (size.width() > 0 && (x >= size.width() || y >= size.height())) throw new IllegalArgumentException(
                x + "," + y + " is off the screen, which is " + size.width() + "x" + size.height() + ".");
            Rect point = new Rect();
            point.left = x; point.right = x + 1; point.top = y; point.bottom = y + 1;
            return new Element("", "", "", point, false, "");
        }
        Screen screen = settled(false);
        // Refs it prints, in its answer or in the choices when several match, can be tapped next.
        number(screen, true);
        String wanted = trimmed.toLowerCase(Locale.ROOT);
        List<Element> exact = new ArrayList<>(), partial = new ArrayList<>();
        for (Element element : screen.elements) {
            String label = element.label.toLowerCase(Locale.ROOT);
            String value = element.value.toLowerCase(Locale.ROOT);
            if (label.equals(wanted) || value.equals(wanted) || element.id.toLowerCase(Locale.ROOT).equals(wanted)) exact.add(element);
            // A list labeled with all of its rows matches each of them, but tapping it taps its middle, not the row.
            else if (label.contains(wanted) && !element.scroll) partial.add(element);
        }
        List<Element> matches = exact.isEmpty() ? partial : exact;
        if (matches.size() > 1) {
            List<Element> actionable = new ArrayList<>();
            for (Element element : matches) if (element.actionable()) actionable.add(element);
            if (actionable.size() == 1) return actionable.get(0);
            if (!actionable.isEmpty()) matches = actionable;
        }
        if (matches.size() == 1) return matches.get(0);
        if (matches.isEmpty()) throw new IllegalArgumentException("Nothing on the screen matches \"" + trimmed + "\"." + scrollHint(screen, trimmed));
        StringBuilder message = new StringBuilder(matches.size() + " things match \"" + trimmed + "\"; name one by its @ref:");
        for (Element element : matches) message.append('\n').append(element.line());
        throw new IllegalArgumentException(message.toString());
    }

    // Acting

    private static final List<String> DIRECTIONS = Arrays.asList("up", "down", "left", "right");

    /** Taps [target], [times] times in a row as quickly as a double tap, or holds it down with [hold]. */
    private String tap(String target, boolean hold, int times) throws Exception {
        if (times < 1 || times > 20) throw new IllegalArgumentException("Tap 1 to 20 times.");
        Element element = find(target);
        int x = element.bounds.centerX(), y = element.bounds.centerY();
        for (int i = 0; i < times; i++) {
            if (i > 0) Thread.sleep(70);
            long down = SystemClock.uptimeMillis();
            touch(down, down, 0, x, y);
            if (hold) Thread.sleep(800);
            touch(down, SystemClock.uptimeMillis(), 1, x, y);
        }
        return element.label.isEmpty() && element.ref == 0 ? x + "," + y : element.line();
    }

    private final MotionEvent.PointerProperties[] oneFinger = {finger(0)};
    private final MotionEvent.PointerCoords[] oneTouch = {new MotionEvent.PointerCoords()};

    /** One finger's touch. It says it's a finger, as a real touch does: some apps, like the launcher, ignore others. */
    private void touch(long downTime, long time, int action, float x, float y) throws Exception {
        oneTouch[0].x = x; oneTouch[0].y = y; oneTouch[0].pressure = 1; oneTouch[0].size = 1;
        MotionEvent event = MotionEvent.obtain(downTime, time, action, 1, oneFinger, oneTouch, 0, 0, 1, 1, 0, 0, 0x1002 /* A touchscreen */, 0);
        try { inject(event); } finally { event.recycle(); }
    }

    /** When input was last sent, after which the screen takes a moment to settle. */
    private long lastAction;
    /**
     * How long the screen must hold still after the last action before it's read. Typing waits longer: search fields
     * wait for typing to pause before they search, and their results show up to a second later.
     */
    private long stillFor = 300;
    private static final long STILL_AFTER_TYPING = 1_000;

    private void inject(InputEvent event) throws Exception {
        lastAction = SystemClock.uptimeMillis();
        stillFor = 300;
        // Without waiting for the app to handle each event, as a finger doesn't; ui() waits for the screen to settle.
        if (!automation().injectInputEvent(event, false)) throw new IllegalStateException("The device refused the input.");
    }

    /**
     * A swipe, or with [drag] a press and hold first, which picks something up to drag: from the start, words[1],
     * through any x,y points after words[2], to the end, words[2]. Either end is anything find() takes; the end can
     * instead be a direction, 60% of the screen that way, far enough for a launcher to open its apps.
     */
    private String gesture(String[] words, boolean drag) throws Exception {
        int ms = option(words, 3, "ms", drag ? 600 : 300);
        if (ms < 1 || ms > 60_000) throw new IllegalArgumentException("Take 1 to 60000 ms.");
        Element from = find(arg(words, 1));
        List<float[]> points = new ArrayList<>();
        points.add(new float[] {from.bounds.centerX(), from.bounds.centerY()});
        for (int i = 3; i < words.length; i++) if (!words[i].contains("=")) points.add(point(words[i]));
        String end = arg(words, 2);
        if (DIRECTIONS.contains(end)) {
            Rect size = screenSize();
            float[] last = points.get(points.size() - 1);
            float x = last[0], y = last[1];
            switch (end) {
                case "up": y -= size.height() * 0.6f; break;
                case "down": y += size.height() * 0.6f; break;
                case "left": x -= size.width() * 0.6f; break;
                default: x += size.width() * 0.6f; break;
            }
            points.add(new float[] {Math.max(0, Math.min(size.width() - 1, x)), Math.max(0, Math.min(size.height() - 1, y))});
        } else {
            Element to = find(end);
            points.add(new float[] {to.bounds.centerX(), to.bounds.centerY()});
        }
        // A long press picks something up, and a moment over the drop target lets it take it.
        move(points, ms, drag ? 700 : 0, drag);
        return "";
    }

    private static float[] point(String text) {
        String[] parts = text.trim().split("\\s*,\\s*");
        try {
            if (parts.length == 2) return new float[] {Integer.parseInt(parts[0]), Integer.parseInt(parts[1])};
        } catch (NumberFormatException invalid) { /* Explained below. */ }
        throw new IllegalArgumentException("Give points as x,y, such as 540,1200, not " + text + ".");
    }

    /**
     * Moves a finger through [points] over [ms] at an even speed. It first holds still for [holdFirst] ms, and with
     * [holdLast] it stops before lifting, so a list scrolls without flinging and a drop target notices the drag.
     */
    private void move(List<float[]> points, int ms, long holdFirst, boolean holdLast) throws Exception {
        float[] along = new float[points.size()];
        for (int i = 1; i < points.size(); i++) {
            float[] a = points.get(i - 1), b = points.get(i);
            along[i] = along[i - 1] + (float) Math.hypot(b[0] - a[0], b[1] - a[1]);
        }
        float total = along[along.length - 1];
        float[] start = points.get(0), end = points.get(points.size() - 1);
        long down = SystemClock.uptimeMillis();
        touch(down, down, 0, start[0], start[1]);
        if (holdFirst > 0) Thread.sleep(holdFirst);
        int steps = Math.max(2, ms / 8);
        int segment = 1;
        for (int step = 1; step <= steps; step++) {
            float at = total * step / steps;
            while (segment < points.size() - 1 && along[segment] < at) segment++;
            float[] a = points.get(segment - 1), b = points.get(segment);
            float span = along[segment] - along[segment - 1];
            float t = span == 0 ? 1 : (at - along[segment - 1]) / span;
            Thread.sleep(Math.max(1, ms / steps));
            touch(down, SystemClock.uptimeMillis(), 2, a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t);
        }
        if (holdLast) {
            Thread.sleep(150);
            touch(down, SystemClock.uptimeMillis(), 2, end[0], end[1]);
        }
        touch(down, SystemClock.uptimeMillis(), 1, end[0], end[1]);
    }

    /** Spreads two fingers apart over [target], or the middle of the screen, to zoom in, or pinches them to zoom out. */
    private String zoom(String direction, String target) throws Exception {
        if (!direction.equals("in") && !direction.equals("out")) throw new IllegalArgumentException("Zoom in or out.");
        Rect area = target != null ? find(target).bounds : screenSize();
        float x = area.centerX(), y = area.centerY();
        float far = Math.min(area.width(), area.height()) * 0.4f, near = far / 4;
        float from = direction.equals("in") ? near : far, to = direction.equals("in") ? far : near;
        MotionEvent.PointerProperties[] fingers = {finger(0), finger(1)};
        MotionEvent.PointerCoords[] at = {new MotionEvent.PointerCoords(), new MotionEvent.PointerCoords()};
        long down = SystemClock.uptimeMillis();
        spread(at, x, y, from);
        touches(down, 0 /* Down */, 1, fingers, at);
        touches(down, 5 | 1 << 8 /* The second finger down */, 2, fingers, at);
        int steps = 40;
        for (int step = 1; step <= steps; step++) {
            Thread.sleep(10);
            spread(at, x, y, from + (to - from) * step / steps);
            touches(down, 2 /* Move */, 2, fingers, at);
        }
        Thread.sleep(100);
        touches(down, 6 | 1 << 8 /* The second finger up */, 2, fingers, at);
        touches(down, 1 /* Up */, 1, fingers, at);
        return "";
    }

    private static MotionEvent.PointerProperties finger(int id) {
        MotionEvent.PointerProperties finger = new MotionEvent.PointerProperties();
        finger.id = id;
        finger.toolType = 1; // A finger.
        return finger;
    }

    /** Puts two fingers [distance] from ([x],[y]) on either side, along a diagonal. */
    private static void spread(MotionEvent.PointerCoords[] at, float x, float y, float distance) {
        float offset = distance / (float) Math.sqrt(2);
        at[0].x = x - offset; at[0].y = y - offset;
        at[1].x = x + offset; at[1].y = y + offset;
        for (MotionEvent.PointerCoords finger : at) { finger.pressure = 1; finger.size = 1; }
    }

    private void touches(long down, int action, int count, MotionEvent.PointerProperties[] fingers, MotionEvent.PointerCoords[] at)
            throws Exception {
        MotionEvent event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, count, fingers, at, 0, 0, 1, 1, 0, 0,
            0x1002 /* A touchscreen */, 0);
        try { inject(event); } finally { event.recycle(); }
    }

    /**
     * Scrolls [target], or the largest list on the screen, so that more of it shows in [direction]. With [until], keeps
     * scrolling until something with that text shows, and returns it.
     */
    private String scroll(String direction, String target, String until) throws Exception {
        if (!DIRECTIONS.contains(direction)) throw new IllegalArgumentException("Scroll up, down, left or right.");
        if (until == null) {
            scrollOnce(direction, target);
            return "";
        }
        String wanted = until.toLowerCase(Locale.ROOT);
        Screen previous = null;
        for (int i = 0; i < 50; i++) {
            Screen screen = settled(false);
            for (Element element : screen.elements) {
                if (!element.label.toLowerCase(Locale.ROOT).contains(wanted)) continue;
                // Its ref can be tapped next, and the screen printed after it still shows everything the scrolling changed.
                number(screen, false);
                return element.line();
            }
            if (previous != null && previous.signature().equals(screen.signature())) {
                throw new IllegalStateException("Scrolled " + direction + " to the end without finding \"" + until + "\".");
            }
            previous = screen;
            scrollOnce(direction, target);
        }
        throw new IllegalStateException("\"" + until + "\" didn't show after 50 scrolls.");
    }

    private void scrollOnce(String direction, String target) throws Exception {
        Rect area;
        if (target != null) area = find(target).bounds;
        else {
            Screen screen = settled(false);
            Element largest = null;
            for (Element element : screen.elements) {
                if (element.scroll && (largest == null
                    || element.bounds.width() * element.bounds.height() > largest.bounds.width() * largest.bounds.height())) largest = element;
            }
            if (largest != null) area = largest.bounds;
            else {
                area = new Rect();
                area.left = screen.size.width() / 10; area.right = screen.size.width() * 9 / 10;
                area.top = screen.size.height() / 5; area.bottom = screen.size.height() * 4 / 5;
            }
        }
        int x = area.centerX(), y = area.centerY();
        int dx = area.width() * 3 / 10, dy = area.height() * 3 / 10;
        // The finger moves against the direction the content reveals.
        switch (direction) {
            case "down": line(x, y + dy, x, y - dy); break;
            case "up": line(x, y - dy, x, y + dy); break;
            case "right": line(x + dx, y, x - dx, y); break;
            default: line(x - dx, y, x + dx, y); break;
        }
    }

    /** Drags in a straight line and stops before lifting, so the list doesn't fling. */
    private void line(int fromX, int fromY, int toX, int toY) throws Exception {
        move(Arrays.asList(new float[] {fromX, fromY}, new float[] {toX, toY}), 300, 0, true);
    }

    private KeyCharacterMap keys;

    /**
     * Types [text], after tapping the field [into] names when it isn't empty, and with [clear] in place of the field's
     * text. Text goes into the focused field through accessibility, all at once, which no screen that's still opening
     * can drop a key of. Fields accessibility can't reach, such as in some games, and passwords it can't read back, get
     * key presses instead.
     */
    private String type(String text, String into, boolean submit, boolean clear) throws Exception {
        String note = "";
        AccessibilityNodeInfo field;
        if (into.isEmpty()) field = focusedField();
        else {
            // Wait for the field just tapped to take focus, not whichever had it before the tap.
            Element target = find(into);
            int x = target.bounds.centerX(), y = target.bounds.centerY();
            tap(x + "," + y, false, 1);
            long deadline = SystemClock.uptimeMillis() + 2_000;
            while (true) {
                field = focusedField();
                Rect bounds = new Rect();
                if (field != null) field.getBoundsInScreen(bounds);
                if (field != null && bounds.contains(x, y)) break;
                if (SystemClock.uptimeMillis() > deadline) {
                    if (field == null) break;
                    throw new IllegalStateException(quoted(target.label) + " didn't take focus when tapped. Tap it, then type.");
                }
                Thread.sleep(30);
            }
        }
        if (!text.isEmpty() || clear) {
            CharSequence current = field == null || clear ? null : field.getText();
            boolean hint = false;
            if (field != null) try { hint = field.isShowingHintText(); } catch (NoSuchMethodError olderAndroid) { }
            String existing = hint || current == null ? "" : current.toString();
            if (field != null && !(field.isPassword() && !existing.isEmpty())) {
                Bundle arguments = new Bundle();
                arguments.putCharSequence("ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE", existing + text);
                if (!field.performAction(0x200000 /* Set text */, arguments)) throw new IllegalStateException("The field didn't take the text.");
                lastAction = SystemClock.uptimeMillis();
                // Fields can refuse or change what's typed, such as a number field dropping letters: say so.
                String wanted = existing + text, shows = "";
                for (int i = 0; i < 10; i++) {
                    Thread.sleep(i == 0 ? 50 : 30);
                    AccessibilityNodeInfo now = focusedField();
                    // An empty field can report its hint as its text.
                    CharSequence value = now == null || showsHint(now) ? null : now.getText();
                    shows = value == null ? "" : value.toString();
                    if (shows.equals(wanted)) break;
                }
                if (!shows.equals(wanted) && !field.isPassword()) note = "The field shows " + quoted(clean(shows)) + ", not " + quoted(clean(wanted)) + ".";
            } else {
                if (field == null && clear) throw new IllegalStateException(noFocus());
                if (keys == null) keys = KeyCharacterMap.load(-1 /* The virtual keyboard */);
                KeyEvent[] events = keys.getEvents(text.toCharArray());
                if (events == null) throw new IllegalStateException(field == null
                    ? noFocus() : "Only keyboard characters can be added to a password that has text.");
                for (KeyEvent event : events) inject(KeyEvent.changeTimeRepeat(event, SystemClock.uptimeMillis(), 0));
                if (field == null) note = "No text field had focus, so the text went to the app as key presses.";
            }
            stillFor = STILL_AFTER_TYPING;
        }
        if (submit) key("enter", false);
        return note;
    }

    /** Why typing found no field, naming a field on the screen last shown to type into. */
    private String noFocus() {
        if (shown != null) for (Element element : shown.elements) {
            if ("field".equals(element.role) && element.ref > 0) return "No text field has focus. Name one with --into, such as --into @" + element.ref + ".";
        }
        return "No text field has focus. Tap one first, or name it with --into.";
    }

    /**
     * The text field with input focus, in the window that has it, which is the one keys go to. Looks through the window
     * for it rather than asking for the focus, which Compose can answer with the field that had focus before.
     */
    private AccessibilityNodeInfo focusedField() throws Exception {
        forget();
        for (AccessibilityWindowInfo window : automation().getWindows()) {
            if (!window.isFocused()) continue;
            AccessibilityNodeInfo root = window.getRoot();
            AccessibilityNodeInfo field = focusedIn(root, 0);
            if (field == null && root != null) {
                AccessibilityNodeInfo focus = root.findFocus(1 /* Input focus */);
                if (focus != null && focus.isEditable()) field = focus;
            }
            return field;
        }
        return null;
    }

    private static AccessibilityNodeInfo focusedIn(AccessibilityNodeInfo node, int depth) {
        if (node == null || depth > 60) return null;
        if (node.isEditable() && node.isFocused()) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo found = focusedIn(node.getChild(i), depth + 1);
            if (found != null) return found;
        }
        return null;
    }

    private static final Map<String, String> KEYS = new HashMap<>();
    static {
        KEYS.put("recents", "APP_SWITCH"); KEYS.put("backspace", "DEL"); KEYS.put("delete", "DEL");
        KEYS.put("select", "DPAD_CENTER"); KEYS.put("up", "DPAD_UP"); KEYS.put("down", "DPAD_DOWN");
        KEYS.put("left", "DPAD_LEFT"); KEYS.put("right", "DPAD_RIGHT"); KEYS.put("play-pause", "MEDIA_PLAY_PAUSE");
        KEYS.put("side", "STEM_PRIMARY"); KEYS.put("crown", "HOME"); KEYS.put("esc", "ESCAPE");
    }

    /** Panels and actions the system opens itself, which no key does, by their global action. */
    private static final Map<String, Integer> PANELS = new HashMap<>();
    static {
        PANELS.put("notifications", 4); PANELS.put("quick-settings", 5); PANELS.put("power-menu", 6); PANELS.put("lock", 8);
    }

    private static final Map<String, int[]> MODIFIERS = new HashMap<>();
    static {
        // Each modifier's key code and the meta state it holds.
        MODIFIERS.put("shift", new int[] {59, 0x41}); MODIFIERS.put("ctrl", new int[] {113, 0x3000});
        MODIFIERS.put("alt", new int[] {57, 0x12}); MODIFIERS.put("meta", new int[] {117, 0x30000});
    }

    /**
     * Presses the key [name], such as back, a, KEYCODE_VOLUME_UP or 4, holding modifiers named before it, such as
     * ctrl+a, and with [hold] as a long press. Opens a system panel, such as notifications, instead when it names one.
     */
    private void key(String name, boolean hold) throws Exception {
        String lower = name.trim().toLowerCase(Locale.ROOT);
        Integer panel = PANELS.get(lower);
        if (panel != null) {
            String before = read(false).signature();
            if (!automation().performGlobalAction(panel)) throw new IllegalStateException("This device can't open " + lower + ".");
            lastAction = SystemClock.uptimeMillis();
            // The panel slides in a moment later, slower than the screen settles on older devices.
            long deadline = SystemClock.uptimeMillis() + 1_500;
            while (SystemClock.uptimeMillis() < deadline) {
                Thread.sleep(50);
                forget();
                if (!read(false).signature().equals(before)) break;
            }
            lastAction = SystemClock.uptimeMillis();
            return;
        }
        String[] parts = lower.split("\\+");
        List<int[]> modifiers = new ArrayList<>();
        int meta = 0;
        for (int i = 0; i < parts.length - 1; i++) {
            int[] modifier = MODIFIERS.get(parts[i].equals("control") ? "ctrl" : parts[i].equals("cmd") ? "meta" : parts[i]);
            if (modifier == null) throw new IllegalArgumentException("Unknown modifier: " + parts[i] + ". Use shift, ctrl, alt or meta.");
            modifiers.add(modifier);
            meta |= modifier[1];
        }
        int code = keyCode(parts[parts.length - 1], name);
        long now = SystemClock.uptimeMillis();
        int held = 0;
        for (int[] modifier : modifiers) {
            held |= modifier[1];
            inject(keyEvent(now, now, 0, modifier[0], 0, held));
        }
        inject(keyEvent(now, now, 0, code, 0, meta));
        if (hold) {
            // Held for a second, repeating as a held key does; Android marks the first repeat as a long press.
            Thread.sleep(400);
            for (int repeat = 1; SystemClock.uptimeMillis() - now < 1_000; repeat++) {
                inject(new KeyEvent(now, SystemClock.uptimeMillis(), 0, code, repeat, meta, -1, 0, 0x8 | (repeat == 1 ? 0x80 : 0), 0x101));
                Thread.sleep(50);
            }
        }
        inject(keyEvent(now, SystemClock.uptimeMillis(), 1, code, 0, meta));
        for (int i = modifiers.size() - 1; i >= 0; i--) {
            held &= ~modifiers.get(i)[1];
            inject(keyEvent(now, SystemClock.uptimeMillis(), 1, modifiers.get(i)[0], 0, held));
        }
    }

    private static KeyEvent keyEvent(long down, long time, int action, int code, int repeat, int meta) {
        return new KeyEvent(down, time, action, code, repeat, meta, -1, 0, 0x8 /* From the system */, 0x101 /* A keyboard */);
    }

    private static int keyCode(String key, String name) {
        if (key.matches("\\d+")) return Integer.parseInt(key);
        String symbol = KEYS.containsKey(key) ? KEYS.get(key) : key.toUpperCase(Locale.ROOT).replace('-', '_');
        int code = KeyEvent.keyCodeFromString(symbol.startsWith("KEYCODE_") ? symbol : "KEYCODE_" + symbol);
        if (code <= 0) throw new IllegalArgumentException("Unknown key: " + name + ".");
        return code;
    }

    /** Waits until [text] shows, or with [gone] until it no longer does, and returns the text that showed. */
    private String waitFor(String text, int timeoutMs, boolean gone) throws Exception {
        String wanted = text.toLowerCase(Locale.ROOT);
        long deadline = SystemClock.uptimeMillis() + timeoutMs;
        while (true) {
            // The cache can still hold the screen before the app changed it, without an event to say so.
            forget();
            Screen screen = read(false);
            Element match = null;
            for (Element element : screen.elements) if (element.label.toLowerCase(Locale.ROOT).contains(wanted)) { match = element; break; }
            if (gone ? match == null : match != null) {
                return gone ? "" : match.label;
            }
            if (SystemClock.uptimeMillis() > deadline) {
                String waited = timeoutMs % 1000 == 0 ? timeoutMs / 1000 + "s" : timeoutMs + "ms";
                throw new IllegalStateException(gone ? "\"" + text + "\" still shows after " + waited + "."
                    : "\"" + text + "\" didn't show within " + waited + ".");
            }
            settle(100, 300);
        }
    }

    /** A JPEG of the screen, scaled down so its shorter side is at most [size]. */
    private byte[] screenshot(int size, int quality) throws Exception {
        // Not mid-transition or with a tap's ripple still fading.
        settle(100, 1_000);
        Bitmap shot = automation().takeScreenshot();
        if (shot == null) throw new IllegalStateException("The device didn't take a screenshot.");
        try {
            Bitmap scaled = shot;
            int shorter = Math.min(shot.getWidth(), shot.getHeight());
            if (size > 0 && shorter > size) {
                float scale = size / (float) shorter;
                scaled = Bitmap.createScaledBitmap(shot, Math.round(shot.getWidth() * scale), Math.round(shot.getHeight() * scale), true);
            }
            ByteArrayOutputStream jpeg = new ByteArrayOutputStream(256 * 1024);
            scaled.compress(Bitmap.CompressFormat.JPEG, quality, jpeg);
            if (scaled != shot) scaled.recycle();
            return jpeg.toByteArray();
        } finally { shot.recycle(); }
    }

    private static final String PACKAGE = "[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+";

    /**
     * Opens [app] as its icon would, or a link, such as a deep link or a web page, in whatever app handles it. With
     * [restart] the app starts over instead of returning to where it was, and with [clear] it also forgets its data,
     * as when it was first installed.
     */
    private String launch(String app, boolean restart, boolean clear) throws Exception {
        String started;
        if (app.matches("[A-Za-z][A-Za-z0-9+.-]*:.+")) {
            if (restart || clear) throw new IllegalArgumentException("Restart or clear an app by its package, not a link.");
            started = shell("am", "start", "-W", "-a", "android.intent.action.VIEW", "-d", app);
        } else {
            if (!app.matches(PACKAGE)) throw new IllegalArgumentException("Name an app by its package, such as com.android.settings, or give a link.");
            String resolved = shell("cmd", "package", "resolve-activity", "--brief", "-c", "android.intent.category.LAUNCHER", app);
            String[] lines = resolved.trim().split("\n");
            String component = lines[lines.length - 1].trim();
            if (!component.contains("/")) throw new IllegalArgumentException(app + " isn't installed, or has nothing to open.");
            if (clear) {
                String cleared = shell("pm", "clear", app);
                if (!cleared.contains("Success")) throw new IllegalStateException(cleared.trim());
            } else if (restart) shell("am", "force-stop", app);
            // -W waits until the app has drawn, so the screen read next shows it. Starting over also clears its task,
            // which can hold screens of other packages, such as Settings' search, that stopping it leaves.
            started = restart || clear ? shell("am", "start", "-W", "-n", component, "-f", "0x10008000" /* A new, cleared task */)
                : shell("am", "start", "-W", "-n", component);
        }
        if (started.contains("unable to resolve Intent")) throw new IllegalArgumentException("Nothing on the device opens " + app + ".");
        if (started.contains("Error")) throw new IllegalStateException(started.trim());
        lastAction = SystemClock.uptimeMillis();
        for (String line : started.split("\n")) if (line.startsWith("Activity: ")) return line.substring(10).trim();
        return app;
    }

    /** Stops [app], as swiping it away from recents and more: nothing of it keeps running. */
    private String close(String app) throws Exception {
        if (!app.matches(PACKAGE)) throw new IllegalArgumentException("Name an app by its package, such as com.android.settings.");
        if (!shell("pm", "path", app).contains("package:")) throw new IllegalArgumentException(app + " isn't installed.");
        shell("am", "force-stop", app);
        lastAction = SystemClock.uptimeMillis();
        return app;
    }

    /** The packages of the apps the launcher shows, one per line. */
    private String apps() throws Exception {
        String found = shell("cmd", "package", "query-activities", "--components", "-a", "android.intent.action.MAIN",
            "-c", "android.intent.category.LAUNCHER");
        java.util.TreeSet<String> packages = new java.util.TreeSet<>();
        for (String line : found.split("\n")) {
            int slash = line.indexOf('/');
            if (slash > 0 && line.substring(0, slash).trim().matches(PACKAGE)) packages.add(line.substring(0, slash).trim());
        }
        if (packages.isEmpty()) throw new IllegalStateException("Couldn't list the apps: " + found.trim());
        return joined(packages);
    }

    // The clipboard, through the clipboard service as the shell, which reads and writes it from the background.

    private Object clipboardService() throws Exception {
        Object binder = Class.forName("android.os.ServiceManager").getMethod("getService", String.class).invoke(null, "clipboard");
        if (binder == null) throw new IllegalStateException("This device has no clipboard.");
        Class<?> stub = Class.forName("android.content.IClipboard$Stub");
        return stub.getMethod("asInterface", Class.forName("android.os.IBinder")).invoke(null, binder);
    }

    /**
     * Calls the clipboard service's [name] with [first] then the caller's details, whose parameters differ between
     * Android versions: the shell's package, no attribution tag, user 0 and the default device.
     */
    private Object callClipboard(String name, Object first) throws Exception {
        Object service = clipboardService();
        for (java.lang.reflect.Method method : service.getClass().getMethods()) {
            if (!method.getName().equals(name)) continue;
            Class<?>[] types = method.getParameterTypes();
            Object[] arguments = new Object[types.length];
            int index = 0, strings = 0;
            if (first != null) {
                if (types.length == 0 || !types[0].isInstance(first)) continue;
                arguments[index++] = first;
            }
            for (; index < types.length; index++) {
                if (types[index] == String.class) arguments[index] = strings++ == 0 ? "com.android.shell" : null;
                else if (types[index] == int.class) arguments[index] = 0;
                else arguments[index] = null;
            }
            return method.invoke(service, arguments);
        }
        throw new IllegalStateException("This device's clipboard can't be reached.");
    }

    private String clipboard() throws Exception {
        Object clip = callClipboard("getPrimaryClip", null);
        if (clip == null) return "";
        if ((int) clip.getClass().getMethod("getItemCount").invoke(clip) == 0) return "";
        Object item = clip.getClass().getMethod("getItemAt", int.class).invoke(clip, 0);
        Object text = item.getClass().getMethod("getText").invoke(item);
        return text == null ? "" : text.toString();
    }

    private String setClipboard(String text) throws Exception {
        Object clip = Class.forName("android.content.ClipData").getMethod("newPlainText", CharSequence.class, CharSequence.class)
            .invoke(null, "robomori", text);
        callClipboard("setPrimaryClip", clip);
        return "";
    }

    // Logs

    private static String appOf(Screen screen) {
        int space = screen.header.indexOf(' ');
        return space < 0 ? screen.header : screen.header.substring(0, space);
    }

    /** [uptime], a time on SystemClock.uptimeMillis(), as logcat's -T takes it: seconds since 1970. */
    private static String logTime(long uptime) {
        long wall = System.currentTimeMillis() - (SystemClock.uptimeMillis() - uptime);
        return String.format(Locale.ROOT, "%d.%03d", wall / 1000, wall % 1000);
    }

    /** Until when crashes were already reported, so each one is reported once. */
    private long crashesShownUntil;

    /**
     * When an app crashed since [since], on SystemClock.uptimeMillis(): a line naming it and its exception, and the first
     * line of the stack trace in the app's own code.
     */
    private String crashSince(long since) {
        try {
            return crashSummary(shell("logcat", "-d", "-b", "crash", "-v", "brief", "-T", logTime(since - 500)));
        } catch (Exception unreadable) { return ""; }
    }

    /** The last crash in [log], from logcat's crash buffer, as crashSince() reports it, or "" when there's none. */
    static String crashSummary(String log) {
        {
            String process = null, exception = null, where = null;
            boolean fatal = false;
            for (String line : log.split("\n")) {
                int colon = line.indexOf("): ");
                String message = colon < 0 ? line.trim() : line.substring(colon + 3).trim();
                if (message.startsWith("FATAL EXCEPTION")) { fatal = true; process = null; exception = null; where = null; }
                else if (fatal && message.startsWith("Process: ")) process = message.substring(9).split(",")[0].trim();
                else if (fatal && process != null && exception == null && !message.isEmpty()) exception = message;
                else if (fatal && process != null && where == null && message.startsWith("at " + process + ".")) where = message;
            }
            if (process == null) return "";
            return "crash " + process + ": " + (exception == null ? "it stopped" : clip(exception))
                + (where == null ? "" : "\n  " + clip(where))
                + "\nrobomori app logs " + process + " shows the whole stack trace.";
        }
    }

    /**
     * What [app], or every app when it's empty, logged since this was last asked for it, or in the last [sinceSeconds]
     * when that's 0 or more: warnings, errors and crashes, or with [all] everything. The first time, the last 2 minutes.
     * Where it got to is kept on the device, so each call shows only what's new.
     */
    private String logs(String app, int sinceSeconds, boolean all) throws Exception {
        String uid = null;
        if (!app.isEmpty()) {
            for (String line : shell("cmd", "package", "list", "packages", "-U", app).split("\n")) {
                String[] parts = line.trim().split("\\s+");
                if (parts.length == 2 && parts[0].equals("package:" + app) && parts[1].startsWith("uid:")) uid = parts[1].substring(4);
            }
            if (uid == null) throw new IllegalArgumentException(app + " isn't installed.");
        }
        long now = SystemClock.uptimeMillis();
        File cursor = new File("/data/local/tmp/.robomori-logs-" + (app.isEmpty() ? "all" : app));
        String since = sinceSeconds >= 0 ? logTime(now - sinceSeconds * 1000L) : null;
        if (since == null) {
            try (InputStream in = new java.io.FileInputStream(cursor)) {
                byte[] bytes = new byte[64];
                int read = in.read(bytes);
                since = read > 0 ? new String(bytes, 0, read, StandardCharsets.UTF_8).trim() : null;
            } catch (IOException none) { /* The first time */ }
        }
        if (since == null) since = logTime(now - 120_000);
        List<String> command = new ArrayList<>(Arrays.asList("logcat", "-d", "-b", "main,system,crash", "-v", "brief", "-T", since));
        List<String> everyApp = new ArrayList<>(command);
        if (uid != null) command.add("--uid=" + uid);
        command.add(all ? "*:V" : "*:W");
        String log = shell(command.toArray(new String[0]));
        // Before Android 12, logcat can't pick an app's lines by its user ID: pick them by its processes' IDs instead.
        if (uid != null && (log.startsWith("logcat: Unknown option") || log.startsWith("Unrecognized Option"))) {
            everyApp.add(all ? "*:V" : "*:W");
            log = onlyProcesses(shell(everyApp.toArray(new String[0])), processesOf(app, since));
        }
        try (FileOutputStream out = new FileOutputStream(cursor)) { out.write(logTime(now).getBytes(StandardCharsets.UTF_8)); }
        return condensed(log, app, all);
    }

    /**
     * The IDs of [app]'s processes since [since]: those running now, those the system started, and those that crashed.
     */
    private static Set<String> processesOf(String app, String since) {
        Set<String> ids = new HashSet<>();
        try {
            for (String id : shell("pidof", app).trim().split("\\s+")) if (!id.isEmpty()) ids.add(id);
            String started = shell("logcat", "-d", "-b", "system,crash", "-v", "brief", "-T", since, "ActivityManager:I", "AndroidRuntime:E", "*:S");
            java.util.regex.Matcher start = java.util.regex.Pattern.compile("Start proc (\\d+):" + java.util.regex.Pattern.quote(app) + "[/:\\s]").matcher(started);
            while (start.find()) ids.add(start.group(1));
            java.util.regex.Matcher crash = java.util.regex.Pattern.compile("Process: " + java.util.regex.Pattern.quote(app) + ", PID: (\\d+)").matcher(started);
            while (crash.find()) ids.add(crash.group(1));
        } catch (Exception unreadable) { /* Only what's running */ }
        return ids;
    }

    /** The lines of [log], in logcat's brief format, from the processes [ids] names. */
    static String onlyProcesses(String log, Set<String> ids) {
        StringBuilder kept = new StringBuilder();
        for (String line : log.split("\n")) {
            int open = line.indexOf('('), close = line.indexOf(')', open + 1);
            if (open < 0 || close < 0) continue;
            if (ids.contains(line.substring(open + 1, close).trim())) kept.append(line).append('\n');
        }
        return kept.toString();
    }

    /**
     * [log] as `robomori app logs` shows it: without emulator noise, each stack trace once, and frames outside [app]'s own
     * code counted instead of listed, unless [all]; the last 300 lines at most.
     */
    static String condensed(String log, String app, boolean all) {
        List<String> lines = new ArrayList<>();
        Set<String> said = new HashSet<>();
        int hidden = 0, scriptFrames = 0, repeats = 0;
        for (String line : log.split("\n")) {
            if (line.startsWith("--------- beginning of") || line.trim().isEmpty()) continue;
            if (!all && isEmulatorNoise(line)) continue;
            int colon = line.indexOf("): ");
            String message = colon < 0 ? line.trim() : line.substring(colon + 3).trim();
            // What older Android logs for every app: its use of hidden APIs, and what SELinux turned down.
            if (!all && (message.startsWith("Accessing hidden ") || message.contains("avc: denied")
                || message.startsWith("Unknown bits set in runtime_flags"))) continue;
            boolean frame = message.startsWith("at ");
            // A JavaScript frame, as React Native logs one: function@line:column, in a bundle with no package to go by.
            boolean script = message.matches("[^\\s@]*@\\d+:\\d+");
            // A stack trace logged twice, as Compose and the runtime both do for a crash, the second time adds nothing.
            if ((frame || message.contains("Exception") || message.contains("Error")) && !said.add(message)) continue;
            // Frames outside the app's own code are the framework calling it: count them instead of listing them. Of
            // a JavaScript trace, the first few are the app's, nearest the error.
            if (!all && ((frame && !app.isEmpty() && !message.startsWith("at " + app + ".")) || (script && ++scriptFrames > 3))) {
                hidden++;
                continue;
            }
            if (!script) scriptFrames = 0;
            if (hidden > 0) { lines.add("    … " + hidden + (hidden == 1 ? " framework frame" : " framework frames")); hidden = 0; }
            // The same line again and again, such as a warning logged each frame: once, and how many more times.
            if (!lines.isEmpty() && lines.get(lines.size() - 1).equals(line)) { repeats++; continue; }
            if (repeats > 0) { lines.add("    … " + (repeats == 1 ? "once more" : repeats + " more times")); repeats = 0; }
            lines.add(line);
        }
        if (repeats > 0) lines.add("    … " + (repeats == 1 ? "once more" : repeats + " more times"));
        if (hidden > 0) lines.add("    … " + hidden + (hidden == 1 ? " framework frame" : " framework frames"));
        int most = 300;
        if (lines.size() > most) {
            int left = lines.size() - most;
            lines = new ArrayList<>(lines.subList(left, lines.size()));
            lines.add(0, "(" + left + " earlier lines left out)");
        }
        if (lines.isEmpty()) return "Nothing new" + (app.isEmpty() ? "" : " from " + app) + (all ? "." : " at warning level or above.");
        return joined(lines);
    }

    /**
     * Warnings an emulator's graphics and system libraries log for every app, which say nothing about the app: tags
     * such as HWUI, EGL and libc. Shown with --all.
     */
    private static boolean isEmulatorNoise(String line) {
        if (line.length() < 3 || line.charAt(0) != 'W' || line.charAt(1) != '/') return false;
        int paren = line.indexOf('(');
        String tag = (paren < 0 ? line.substring(2) : line.substring(2, paren)).trim();
        return tag.equals("HWUI") || tag.equals("libc") || tag.startsWith("EGL") || tag.equals("OpenGLRenderer")
            || tag.startsWith("Gralloc") || tag.equals("skia") || tag.equals("InputConnectionAdaptor") || tag.equals("RemoteInputConnectionImpl")
            || tag.equals("InputConnectionWrapper") || tag.equals("ziparchive") || tag.equals("nativeloader")
            // React Native's own lifecycle, logged as warnings for every app built with it.
            || tag.equals("unknown:BridgelessReact") || tag.equals("unknown:ViewManagerPropertyUpdater")
            || tag.equals("InteractionJankMonitor");
    }

    /** Runs [command] on the device, stopping it after 20 seconds so an app that never opens can't hold up the agent. */
    private static String shell(String... command) throws Exception {
        final Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        Thread limit = new Thread(() -> {
            try { Thread.sleep(20_000); process.destroy(); } catch (InterruptedException finished) { }
        }, "robomori-agent-shell-limit");
        limit.setDaemon(true);
        limit.start();
        InputStream output = process.getInputStream();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        for (int read; (read = output.read(buffer)) > 0; ) bytes.write(buffer, 0, read);
        process.waitFor();
        limit.interrupt();
        return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
    }
}
