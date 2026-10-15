/* SPDX-License-Identifier: GPL-3.0-only */
package com.droiddeck.launcher.gpu;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parse a GPU name, not a MediaTek marketing label. The kit is validated on the G615; the
 * driver itself (PanVK-kbase universal ICD) covers CSF Mali v9-v13, G925 included. */
public final class MaliProfile {
    private static final Pattern NAME = Pattern.compile(
            "\\b((?:Mali|Immortalis)-G([0-9]{2,4})(?:\\s+MC[0-9]+)?)\\b", Pattern.CASE_INSENSITIVE);
    private MaliProfile() {}
    public static String name(String text) {
        if (text == null) return "";
        Matcher m = NAME.matcher(text);
        return m.find() ? m.group(1) : "";
    }
    public static int model(String text) {
        if (text == null) return 0;
        Matcher m = NAME.matcher(text);
        return m.find() ? Integer.parseInt(m.group(2)) : 0;
    }
    public static boolean isG615(String text) {
        return model(text) == 615;
    }
}
