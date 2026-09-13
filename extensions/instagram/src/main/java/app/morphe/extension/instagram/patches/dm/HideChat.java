/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.instagram.patches.dm;

import static app.morphe.extension.instagram.utils.IgStr.str;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.widget.PopupWindow;

import app.morphe.extension.crimera.PikoUtils;
import app.morphe.extension.instagram.entity.UserData;
import app.morphe.extension.instagram.utils.Pref;

import com.instagram.model.direct.DirectThreadKey;
import com.instagram.common.session.UserSession;


@SuppressWarnings("unused")
public class HideChat {

    /** Tag used to identify our injected long-press menu entry. */
    private static final String HIDE_BUTTON_TAG = "HIDE_CHAT";

    /** Row label shown in the long-press dialog. */
    private static final String HIDE_CHAT_LABEL = "Hide chat";

    /**
     * Thread id of the long-press dialog currently being built. Stashed by
     * the patched dialog builder at method entry, consumed by
     * {@link #injectHideChatRow(Object, List)}.
     */
    public static String pendingThreadId;

    /** Obfuscated long-press row model class (LX/0VTM in v439). */
    private static final String ROW_MODEL_CLASS = "X.0VTM";
    /** Obfuscated row click-listener interface (LX/0nQi in v439). */
    private static final String ROW_LISTENER_CLASS = "X.0nQi";

    private static final String DIRECT_THREAD_KEY_CLASS = "com.instagram.model.direct.DirectThreadKey";

    // Cached reflection lookups (single pinned IG version, so caching is safe).
    private static volatile Field sThreadKeyField;
    private static volatile Field sThreadKeyIdField;

    // ------------------------------------------------------------------
    // Hidden set helpers.
    // ------------------------------------------------------------------

    public static Set<String> getHiddenThreadIds() {
        try {
            Set<String> ids = Pref.hiddenChatThreadIds();
            return ids != null ? ids : new HashSet<String>();
        } catch (Exception e) {
            PikoUtils.logger(e);
            return new HashSet<String>();
        }
    }

    public static boolean isHidden(String threadId) {
        return threadId != null && getHiddenThreadIds().contains(threadId);
    }

    public static void setHidden(String threadId, boolean hidden) {
        if (threadId == null || threadId.isEmpty()) return;
        try {
            Set<String> ids = new HashSet<String>(getHiddenThreadIds());
            if (hidden) {
                ids.add(threadId);
            } else {
                ids.remove(threadId);
                removeStoredName(threadId);
            }
            Pref.setHiddenChatThreadIds(ids);
        } catch (Exception e) {
            PikoUtils.logger(e);
        }
    }

    /** Display name stored as "threadId|name" entries; best-effort only. */
    public static String getStoredName(String threadId) {
        if (threadId == null) return null;
        try {
            Set<String> entries = Pref.hiddenChatThreadNames();
            if (entries == null) return null;
            String prefix = threadId + "|";
            for (String entry : entries) {
                if (entry != null && entry.startsWith(prefix)) {
                    String name = entry.substring(prefix.length());
                    return name.isEmpty() ? null : name;
                }
            }
        } catch (Exception e) {
            PikoUtils.logger(e);
        }
        return null;
    }

    public static void storeName(String threadId, String name) {
        if (threadId == null || name == null || name.isEmpty()) return;
        try {
            Set<String> entries = new HashSet<String>();
            Set<String> current = Pref.hiddenChatThreadNames();
            if (current != null) entries.addAll(current);
            String prefix = threadId + "|";
            entries.removeIf(e -> e != null && e.startsWith(prefix));
            entries.add(prefix + name);
            Pref.setHiddenChatThreadNames(entries);
        } catch (Exception e) {
            PikoUtils.logger(e);
        }
    }

    private static void removeStoredName(String threadId) {
        if (threadId == null) return;
        try {
            Set<String> current = Pref.hiddenChatThreadNames();
            if (current == null || current.isEmpty()) return;
            Set<String> entries = new HashSet<String>(current);
            String prefix = threadId + "|";
            if (entries.removeIf(e -> e != null && e.startsWith(prefix))) {
                Pref.setHiddenChatThreadNames(entries);
            }
        } catch (Exception e) {
            PikoUtils.logger(e);
        }
    }

    // ------------------------------------------------------------------
    // Inbox filter. Called from the DM thread deserializer with each parsed
    // DirectThread; returning null drops the thread from the inbox list.
    // ------------------------------------------------------------------

    public static Object filter(Object threadObject) {
        try {
            if (!Pref.enableHideChatOption()) return threadObject;
            Set<String> hidden = getHiddenThreadIds();
            if (hidden.isEmpty()) return threadObject;
            String threadId = getThreadId(threadObject);
            if (threadId == null || !hidden.contains(threadId)) return threadObject;
            harvestThreadName(threadId, threadObject);
            return null;
        } catch (Exception e) {
            PikoUtils.logger(e);
        }
        return threadObject;
    }

    /** Reads the thread id from a DirectThread via its DirectThreadKey field (A00). */
    private static String getThreadId(Object threadObject) {
        if (threadObject == null) return null;
        try {
            Field keyField = sThreadKeyField;
            if (keyField == null) {
                keyField = findFieldOfType(threadObject.getClass(), DIRECT_THREAD_KEY_CLASS);
                if (keyField == null) return null;
                sThreadKeyField = keyField;
            }
            Object key = keyField.get(threadObject);
            if (key == null) return null;
            Field idField = sThreadKeyIdField;
            if (idField == null) {
                idField = key.getClass().getDeclaredField("A00");
                idField.setAccessible(true);
                sThreadKeyIdField = idField;
            }
            Object value = idField.get(key);
            return value instanceof String ? (String) value : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static Field findFieldOfType(Class<?> clazz, String typeName) {
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            for (Field field : current.getDeclaredFields()) {
                if (field.getType().getName().equals(typeName)) {
                    field.setAccessible(true);
                    return field;
                }
            }
            current = current.getSuperclass();
        }
        return null;
    }

    /**
     * Best-effort display-name harvest for hidden threads, so the settings
     * list can show "@user1, @user2" instead of a bare thread id. Runs off
     * the calling thread and never throws.
     */
    private static void harvestThreadName(final String threadId, final Object threadObject) {
        try {
            if (getStoredName(threadId) != null) return;
        } catch (Exception ignored) {
            return;
        }
        new Thread(() -> {
            try {
                List<String> names = new ArrayList<String>();
                Class<?> current = threadObject.getClass();
                while (current != null && current != Object.class && names.size() < 4) {
                    for (Field field : current.getDeclaredFields()) {
                        if (!List.class.isAssignableFrom(field.getType())) continue;
                        field.setAccessible(true);
                        Object value = field.get(threadObject);
                        if (!(value instanceof List)) continue;
                        for (Object item : (List<?>) value) {
                            if (item == null || names.size() >= 4) break;
                            try {
                                String username = new UserData(item).getUsername();
                                if (username != null && !username.isEmpty()
                                        && !names.contains("@" + username)) {
                                    names.add("@" + username);
                                }
                            } catch (Exception ignored) {
                                // Not a user object; keep scanning.
                            }
                        }
                        if (!names.isEmpty()) break;
                    }
                    if (!names.isEmpty()) break;
                    current = current.getSuperclass();
                }
                if (!names.isEmpty()) {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < names.size(); i++) {
                        if (i > 0) sb.append(", ");
                        sb.append(names.get(i));
                    }
                    storeName(threadId, sb.toString());
                }
            } catch (Exception e) {
                PikoUtils.logger(e);
            }
        }).start();
    }

    // ------------------------------------------------------------------
    // Long-press menu entry point. The patched dialog builder stashes the
    // thread id in {@link #pendingThreadId}, then calls
    // {@link #injectHideChatRow(Object, List)} after the stock rows are
    // assembled. We append a genuine row model (LX/0VTM) with a dynamic
    // Proxy implementing the row click listener (LX/0nQi).
    // ------------------------------------------------------------------

    /**
     * Appends the "Hide chat" row to the long-press dialog's row list.
     *
     * @param dialog the dialog controller (a PopupWindow subclass); its
     *               Context field is used for hiding + refresh.
     * @param rows   the mutable row-model list about to be shown.
     */
    public static void injectHideChatRow(Object dialog, List rows) {
        String threadId = pendingThreadId;
        pendingThreadId = null;
        if (threadId == null || threadId.isEmpty() || dialog == null || rows == null) return;
        try {
            if (!Pref.enableHideChatOption()) return;
        } catch (Exception e) {
            return;
        }

        // The dialog's Context field (A02 in v439); fall back to the app context.
        Context context = extractDialogContext(dialog);
        if (context == null) {
            try {
                context = PikoUtils.getContext();
            } catch (Exception ignored) {
            }
        }
        if (context == null) return;
        final Context ctx = context;
        final String tid = threadId;
        final Object dlg = dialog;

        try {
            ClassLoader loader = HideChat.class.getClassLoader();
            Class<?> rowClass = Class.forName(ROW_MODEL_CLASS, false, loader);
            Class<?> listenerIface = Class.forName(ROW_LISTENER_CLASS, false, loader);

            // Row click listener: BsU() = enabled flag, Esq() = on tap.
            Object listener = Proxy.newProxyInstance(
                    loader,
                    new Class<?>[]{listenerIface},
                    (proxy, method, args) -> {
                        String name = method.getName();
                        if ("Esq".equals(name)) {
                            try {
                                ((PopupWindow) dlg).dismiss();
                            } catch (Exception ignored) {
                            }
                            hideThread(ctx, tid);
                            return null;
                        }
                        if ("BsU".equals(name)) return Boolean.TRUE;
                        return null;
                    });

            // Reuse the first stock row's icon so ours matches the menu style.
            Drawable icon = null;
            try {
                if (!rows.isEmpty()) {
                    Object first = rows.get(0);
                    Field iconField = first.getClass().getDeclaredField("A00");
                    iconField.setAccessible(true);
                    Object iconObj = iconField.get(first);
                    if (iconObj instanceof Drawable) icon = (Drawable) iconObj;
                }
            } catch (Exception ignored) {
            }

            // LX/0VTM(<Drawable>, <Drawable>, LX/0nQi;, Integer, String, String,
            //          Z, Z, Z, Z, Z)
            // p3 (listener) and p5 (label) must be non-null.
            Constructor<?> ctor = rowClass.getConstructor(
                    Drawable.class, Drawable.class, listenerIface,
                    Integer.class, String.class, String.class,
                    boolean.class, boolean.class, boolean.class, boolean.class, boolean.class);
            Object row = ctor.newInstance(
                    icon, null, listener, null, HIDE_CHAT_LABEL, null,
                    false, false, false, false, false);

            // noinspection unchecked
            rows.add(row);
        } catch (Exception e) {
            PikoUtils.logger(e);
        }
    }

    /** Reads the dialog's Context field (A02 in v439). */
    private static Context extractDialogContext(Object dialog) {
        try {
            Field f = dialog.getClass().getDeclaredField("A02");
            f.setAccessible(true);
            Object value = f.get(dialog);
            return value instanceof Context ? (Context) value : null;
        } catch (Exception e) {
            return null;
        }
    }

    // Return true = consume the press (skip Instagram's handling).
    // Return false = let Instagram handle it.
    // Kept for API compatibility; the row-injection path above is authoritative.
    public static boolean buttonAction(Context context, UserSession userSession,
                                       Object buttonPressed, Object threadInfo,
                                       DirectThreadKey directThreadKey) {
        return false;
    }

    private static void hideThread(Context context, String threadId) {
        if (threadId == null || threadId.isEmpty()) return;
        setHidden(threadId, true);
        try {
            android.widget.Toast.makeText(context, str("piko_chat_hidden"), android.widget.Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            PikoUtils.logger(e);
        }
        // Refresh the inbox so the thread disappears immediately. Unwrap to
        // the host Activity and recreate it; the deserializer filter drops the
        // hidden thread on reload.
        try {
            Context base = context;
            int depth = 0;
            while (base instanceof ContextWrapper && !(base instanceof Activity) && depth < 8) {
                base = ((ContextWrapper) base).getBaseContext();
                depth++;
            }
            if (base instanceof Activity) {
                ((Activity) base).recreate();
            }
        } catch (Exception e) {
            PikoUtils.logger(e);
        }
    }

    // ------------------------------------------------------------------
    // Piko settings entry point.
    // ------------------------------------------------------------------

    public static void openHiddenChats(Context ctx) {
        try {
            if (ctx == null) ctx = PikoUtils.getContext();
            if (ctx == null) return;
            Intent intent = new Intent(ctx, HiddenChatsActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(intent);
        } catch (Exception e) {
            PikoUtils.logger(e);
        }
    }
}
