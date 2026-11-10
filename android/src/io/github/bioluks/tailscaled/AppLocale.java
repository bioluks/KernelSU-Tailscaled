package io.github.bioluks.tailscaled;

import android.content.Context;
import android.content.res.Configuration;
import java.util.Locale;

final class AppLocale {
    static Context wrap(Context base) {
        String language = base.getSharedPreferences("companion", 0).getString("language", "en");
        Configuration config = new Configuration(base.getResources().getConfiguration());
        config.setLocale(Locale.forLanguageTag(language));
        return base.createConfigurationContext(config);
    }
    static void toggle(Context context) {
        String current = context.getSharedPreferences("companion", 0).getString("language", "en");
        context.getSharedPreferences("companion", 0).edit()
                .putString("language", current.equals("en") ? "zh" : "en").apply();
    }
}
