/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.instagram.patches.dm;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.drawable.Drawable;
import android.widget.PopupWindow;
import android.widget.Toast;

/**
 * Standalone, reflection-only injector for the "Hide chat" long-press row.
 *
 * <p>The "Hide chat" patch calls into this class from injected smali, so it
 * deliberately avoids compile-time dependencies on the extension or on
 * Instagram internals: everything outside {@code android.*} is reached via
 * reflection and it compiles against {@code android.jar} alone. It is
 * packaged as a small standalone extension DEX (hidechat.mpe) merged at APK
 * build time.
 */
public class HideChatInjector {

    private static final String HIDE_CHAT_CLASS =
            "app.morphe.extension.instagram.patches.dm.HideChat";
    private static final String HIDE_CHAT_LABEL = "Hide chat";

    /** Obfuscated long-press row model class (LX/0VTM in v439). */
    private static final String ROW_MODEL_CLASS = "X.0VTM";
    /** Obfuscated row click-listener interface (LX/0nQi in v439). */
    private static final String ROW_LISTENER_CLASS = "X.0nQi";

    /**
     * Thread id of the long-press dialog currently being built. Stashed by
     * {@link #stashThreadKey(Object)} at dialog-builder entry, consumed by
     * {@link #injectHideChatRow(Object, List)}.
     */
    public static String pendingThreadId;

    private HideChatInjector() {
    }

    /**
     * Stashes the thread id from a {@code DirectThreadKey} (its {@code A00}
     * field in v439) for the dialog builder that is about to run.
     */
    public static void stashThreadKey(Object threadKey) {
        if (threadKey == null) {
            pendingThreadId = null;
            return;
        }
        try {
            Field field = threadKey.getClass().getDeclaredField("A00");
            field.setAccessible(true);
            Object value = field.get(threadKey);
            pendingThreadId = value instanceof String ? (String) value : null;
        } catch (Exception e) {
            pendingThreadId = null;
        }
    }

    /**
     * Appends the "Hide chat" row to the long-press dialog's row list.
     *
     * @param dialog the dialog controller; its Context field is used for
     *               hiding + refresh.
     * @param rows   the mutable row-model list about to be shown.
     */
    public static void injectHideChatRow(Object dialog, List rows) {
        String threadId = pendingThreadId;
        pendingThreadId = null;
        if (threadId == null || threadId.isEmpty() || dialog == null || rows == null) return;
        try {
            if (!isHideChatEnabled()) return;
        } catch (Exception e) {
            return;
        }

        Context context = extractDialogContext(dialog);
        if (context == null) context = getAppContext();
        if (context == null) return;
        final Context ctx = context;
        final String tid = threadId;
        final Object dlg = dialog;

        try {
            ClassLoader loader = HideChatInjector.class.getClassLoader();
            Class<?> rowClass = Class.forName(ROW_MODEL_CLASS, false, loader);
            Class<?> listenerIface = Class.forName(ROW_LISTENER_CLASS, false, loader);

            // Row click listener: BsU() = enabled flag, Esq() = on tap.
            Object listener =
                    Proxy.newProxyInstance(
                            loader,
                            new Class<?>[] {listenerIface},
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

            // LX/0VTM(<Drawable>, <Drawable>, LX/0nQi;, Integer, String,
            //          String, Z, Z, Z, Z, Z)
            // p3 (listener) and p5 (label) must be non-null.
            Constructor<?> ctor =
                    rowClass.getConstructor(
                            Drawable.class,
                            Drawable.class,
                            listenerIface,
                            Integer.class,
                            String.class,
                            String.class,
                            boolean.class,
                            boolean.class,
                            boolean.class,
                            boolean.class,
                            boolean.class);
            Object row =
                    ctor.newInstance(
                            icon,
                            null,
                            listener,
                            null,
                            HIDE_CHAT_LABEL,
                            null,
                            false,
                            false,
                            false,
                            false,
                            false);

            // noinspection unchecked
            rows.add(row);
        } catch (Exception e) {
            log(e);
        }
    }

    /** Reflective read of {@code Pref.enableHideChatOption()}. */
    private static boolean isHideChatEnabled() {
        try {
            Class.forName(HIDE_CHAT_CLASS);
            Class<?> pref = Class.forName("app.morphe.extension.instagram.utils.Pref");
            Method method = pref.getDeclaredMethod("enableHideChatOption");
            Object result = method.invoke(null);
            return result instanceof Boolean && (Boolean) result;
        } catch (Exception e) {
            return false;
        }
    }

    /** Reads the dialog's Context field (A02 in v439). */
    private static Context extractDialogContext(Object dialog) {
        try {
            Field field = dialog.getClass().getDeclaredField("A02");
            field.setAccessible(true);
            Object value = field.get(dialog);
            return value instanceof Context ? (Context) value : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Best-effort application context via the shared/extension utils. */
    private static Context getAppContext() {
        try {
            Class<?> utils = Class.forName("app.morphe.extension.shared.Utils");
            Method method = utils.getDeclaredMethod("getContext");
            Object result = method.invoke(null);
            if (result instanceof Context) return (Context) result;
        } catch (Exception ignored) {
        }
        try {
            Class<?> utils = Class.forName("app.morphe.extension.crimera.PikoUtils");
            Method method = utils.getDeclaredMethod("getContext");
            Object result = method.invoke(null);
            if (result instanceof Context) return (Context) result;
        } catch (Exception ignored) {
        }
        return null;
    }

    private static void hideThread(Context context, String threadId) {
        try {
            Class<?> hideChat = Class.forName(HIDE_CHAT_CLASS);
            Method setHidden =
                    hideChat.getDeclaredMethod("setHidden", String.class, boolean.class);
            setHidden.invoke(null, threadId, Boolean.TRUE);
        } catch (Exception e) {
            log(e);
            return;
        }

        try {
            Class<?> igStr = Class.forName("app.morphe.extension.instagram.utils.IgStr");
            Method str = igStr.getDeclaredMethod("str", String.class);
            String text = (String) str.invoke(null, "piko_chat_hidden");
            Toast.makeText(context, text, Toast.LENGTH_SHORT).show();
        } catch (Exception ignored) {
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
        } catch (Exception ignored) {
        }
    }

    private static void log(Exception e) {
        try {
            Class<?> utils = Class.forName("app.morphe.extension.crimera.PikoUtils");
            Method logger = utils.getDeclaredMethod("logger", Exception.class);
            logger.invoke(null, e);
        } catch (Exception ignored) {
        }
    }
}
