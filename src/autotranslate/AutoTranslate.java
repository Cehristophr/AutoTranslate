package autotranslate;

import arc.Core;
import arc.Events;
import arc.func.Cons;
import arc.scene.style.TextureRegionDrawable;
import arc.util.*;
import com.deepl.api.*;
import mindustry.Vars;
import mindustry.game.EventType;
import mindustry.mod.Mod;
import mindustry.ui.dialogs.FullTextDialog;
import mindustry.ui.dialogs.SettingsMenuDialog;

import java.util.concurrent.atomic.AtomicReference;

import static arc.Core.bundle;
import static mindustry.Vars.ui;

import java.util.HashMap;
import java.util.Map;

public class AutoTranslate extends Mod {
    String lastMessage = "";

    String authKey;

    private final Map<String, TextResult> messageCache = new HashMap<>();

    @Override
    public void init() {
        Cons<SettingsMenuDialog.SettingsTable> builder = settingsTable -> {
            AtomicReference<String> authKey = new AtomicReference<>(Core.settings.getString("auth-key", ""));
            AtomicReference<String> targetLanguage = new AtomicReference<>(Core.settings.getString("target-language", "en-GB"));

            SettingsMenuDialog.SettingsTable settings = new SettingsMenuDialog.SettingsTable();
            settings.areaTextPref(bundle.get("auto-translate.settings.auth-key"), "", authKey::set);
            settings.textPref(bundle.get("auto-translate.settings.target-language"), "en-GB", targetLanguage::set);

            settings.pref(new ButtonSetting("Save", () -> {
                try {
                    Translator translator = new Translator(authKey.get());

                    if (translator.getTargetLanguages().stream().anyMatch(l -> l.getCode().equals(targetLanguage.get()))) {
                        this.authKey = authKey.get();
                        Core.settings.put("auth-key", this.authKey);
                        Core.settings.put("target-language", targetLanguage.get());

                        showDialog("Auto Translation", "Successfully configured Auto Translate");
                    } else {
                        StringBuilder languages = new StringBuilder("Available languages (enter language code):\n\n");

                        for (Language l : translator.getTargetLanguages()) {
                            languages.append(l.getName()).append(" - ").append(l.getCode()).append("\n");
                        }

                        showDialog("Error", languages.toString());
                    }
                } catch (IllegalArgumentException | DeepLException | InterruptedException e) {
                    showDialog("Error", "Something went wrong: " + e.getMessage());
                    Log.err("Auto Translate: " + e.getMessage());
                }
            }));

            settings.checkPref(bundle.get("auto-translate.settings.enabled"), true, e -> Core.settings.put("auto-translate-enabled", e));

            settingsTable.add(settings);
        };
        ui.settings.getCategories().add(new SettingsMenuDialog.SettingsCategory(bundle.get("auto-translate.settings.title"), new TextureRegionDrawable(Core.atlas.find("auto-translate-logo")), builder));

        authKey = Core.settings.getString("auth-key", "");

        if (authKey.isEmpty()) {
            showDialog("Auto Translate", "Failed to initialize, invalid or empty authentication key, go to settings and enter a valid Deepl authentication key");
        }

        Events.on(EventType.PlayerChatEvent.class, e -> {
            String newMessage = Strings.stripColors(e.message).trim();

            if (Core.settings.getBool("auto-translate-enabled", true)) {
                if (newMessage.isEmpty())
                    return;

                if(e.player == null)
                    return;

                if(Vars.player == null)
                    return;

                if(Vars.player.id == e.player.id)
                    return;

                if(newMessage.startsWith("+"))
                    return;

                if(lastMessage.equals(newMessage))
                    return;

                lastMessage = newMessage;

                Threads.thread(() -> {
                    try {
                        Translator translator = new Translator(authKey);
                        String language = Core.settings.getString("target-language", "en-GB");

                        TextResult translatedMessage = translateString(translator, language, newMessage);

                        if(translatedMessage == null)
                        {
                            Log.err("Failed to translate message: translatedMessage == null");
                            Vars.player.sendMessage("[red]Failed to translate message");
                            return;
                        }

                        String translation = translatedMessage.getText().trim() + " [lightgray] (" + translatedMessage.getDetectedSourceLanguage() + ")";

                        if(e.player.name() != null)
                            translation = "["+ Strings.stripColors(e.player.name()) +"]: " + translatedMessage.getText().trim() + " [lightgray] (" + translatedMessage.getDetectedSourceLanguage() + ")";

                        Vars.player.sendMessage(translation);
                    } catch (DeepLException | InterruptedException error) {
                        Log.err("Failed to translate message: " + error.getMessage());
                        Vars.player.sendMessage("[red]Failed to translate message");
                    }
                });
            }
        });
    }


    private void showDialog(String title, String message) {
        FullTextDialog baseDialog = new FullTextDialog();

        baseDialog.show(title, message);
    }

    private static class ButtonSetting extends SettingsMenuDialog.SettingsTable.Setting {
        String name;
        Runnable clicked;

        public ButtonSetting(String name, Runnable clicked) {
            super(name);
            this.name = name;
            this.clicked = clicked;
        }

        @Override
        public void add(SettingsMenuDialog.SettingsTable table) {
            table.button(name, clicked).margin(14).width(240f).pad(6);
            table.row();
        }


    }

    private String getMD5FromString(String text) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] array = md.digest(text.getBytes());
            StringBuilder sb = new StringBuilder();
            for (byte b : array) {
                sb.append(Integer.toHexString((b & 0xFF) | 0x100), 1, 3);
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            return null;
        }
    }

    private TextResult translateString(Translator translator, String language, String finalMessage) throws DeepLException, InterruptedException {
        String messageKey = getMD5FromString(language + finalMessage);
        if(messageCache.containsKey(messageKey))
            return messageCache.get(messageKey);

        TextResult result = translator.translateText(finalMessage, null, language);
        if (result.getDetectedSourceLanguage().equals(LanguageCode.standardize(language)))
            return null;

        messageCache.put(messageKey, result);
        return result;
    }


}
