package io.github.bioluks.tailscaled;

import android.content.Context;
import android.content.res.Configuration;
import java.util.Locale;

final class AppLocale {
    static Context wrap(Context base) {
        String language = language(base);
        Configuration config = new Configuration(base.getResources().getConfiguration());
        config.setLocale(Locale.forLanguageTag(language));
        return base.createConfigurationContext(config);
    }
    static String language(Context context) {
        String saved = context.getSharedPreferences("companion", Context.MODE_PRIVATE)
                .getString("language", "en");
        return "zh".equals(saved) ? "zh" : "en";
    }

    static void setLanguage(Context context, String language) {
        if (!"en".equals(language) && !"zh".equals(language)) {
            throw new IllegalArgumentException("Unsupported app language");
        }
        context.getSharedPreferences("companion", Context.MODE_PRIVATE).edit()
                .putString("language", language).apply();
    }
}
